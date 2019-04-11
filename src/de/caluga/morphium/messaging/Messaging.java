package de.caluga.morphium.messaging;

import de.caluga.morphium.Morphium;
import de.caluga.morphium.ShutdownListener;
import de.caluga.morphium.async.AsyncOperationCallback;
import de.caluga.morphium.async.AsyncOperationType;
import de.caluga.morphium.changestream.ChangeStreamMonitor;
import de.caluga.morphium.driver.MorphiumId;
import de.caluga.morphium.query.MorphiumIterator;
import de.caluga.morphium.query.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * User: Stephan Bösebeck
 * Date: 26.05.12
 * Time: 15:48
 * <p/>
 * Messaging implements a simple, threadsafe and messaging api. Used for cache synchronization.
 * Msg can have several modes:
 * - LockedBy set to ALL (Exclusive Messages): every listener may process it (in parallel), means 1->n. e.g. Cache sync
 * - LockedBy null (non exclusive messages): only one listener at a time
 * - Message listeners may return a Message as answer. Or throw a MessageRejectedException.c
 */
@SuppressWarnings({"ConstantConditions", "unchecked", "UnusedDeclaration"})
public class Messaging extends Thread implements ShutdownListener {
    private static Logger log = LoggerFactory.getLogger(Messaging.class);

    private Morphium morphium;
    private boolean running;
    private int pause;
    private String id;
    private boolean autoAnswer = false;
    private String hostname;
    private boolean processMultiple;

    private List<MessageListener> listeners;


    private Map<String, Long> pauseMessages = new ConcurrentHashMap<>();
    private Map<String, List<MessageListener>> listenerByName;
    private String queueName;

    private ThreadPoolExecutor threadPool;
    private ScheduledThreadPoolExecutor decouplePool;

    private boolean multithreadded;
    private int windowSize;
    private boolean useChangeStream;
    private ChangeStreamMonitor changeStreamMonitor;

    private Map<MorphiumId, Msg> waitingForAnswers = new ConcurrentHashMap<>();
    private Map<MorphiumId, Msg> waitingForMessages = new ConcurrentHashMap<>();

    private List<MorphiumId> processing = new Vector<>();

    /**
     * attaches to the default queue named "msg"
     *
     * @param m               - morphium
     * @param pause           - pause between checks
     * @param processMultiple - process multiple messages at once, if false, only ony by one
     */
    public Messaging(Morphium m, int pause, boolean processMultiple) {
        this(m, null, pause, processMultiple);
    }

    public Messaging(Morphium m) {
        this(m, null, 500, false, false, 100);
    }

    public Messaging(Morphium m, int pause, boolean processMultiple, boolean multithreadded, int windowSize) {
        this(m, null, pause, processMultiple, multithreadded, windowSize);
    }

    public Messaging(Morphium m, String queueName, int pause, boolean processMultiple) {
        this(m, queueName, pause, processMultiple, false, 1000);
    }

    public Messaging(Morphium m, String queueName, int pause, boolean processMultiple, boolean multithreadded, int windowSize) {
        this(m, queueName, pause, processMultiple, multithreadded, windowSize, m.isReplicaSet());
    }

    public Messaging(Morphium m, String queueName, int pause, boolean processMultiple, boolean multithreadded, int windowSize, boolean useChangeStream) {
        this.multithreadded = multithreadded;
        this.windowSize = windowSize;
        morphium = m;
        this.useChangeStream = useChangeStream;


        if (multithreadded) {
            BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>() {
                @Override
                public boolean offer(Runnable e) {
                    /*
                     * Offer it to the queue if there is 0 items already queued, else
                     * return false so the TPE will add another thread. If we return false
                     * and max threads have been reached then the RejectedExecutionHandler
                     * will be called which will do the put into the queue.
                     */
//                    if (size() == 0) {
//                        return super.offer(e);
//                    } else {
//                        return false;
//                    }
                    int poolSize = threadPool.getPoolSize();
                    int maximumPoolSize = threadPool.getMaximumPoolSize();
                    if (poolSize >= maximumPoolSize || poolSize > threadPool.getActiveCount()) {
                        return super.offer(e);
                    } else {
                        return false;
                    }
                }
            };
            threadPool = new ThreadPoolExecutor(morphium.getConfig().getThreadPoolMessagingCoreSize(), morphium.getConfig().getThreadPoolMessagingMaxSize(),
                    morphium.getConfig().getThreadPoolMessagingKeepAliveTime(), TimeUnit.MILLISECONDS,
                    queue);
            threadPool.setRejectedExecutionHandler((r, executor) -> {
                try {
                    /*
                     * This does the actual put into the queue. Once the max threads
                     * have been reached, the tasks will then queue up.
                     */
                    executor.getQueue().put(r);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            //noinspection unused,unused
            threadPool.setThreadFactory(new ThreadFactory() {
                private AtomicInteger num = new AtomicInteger(1);

                @Override
                public Thread newThread(Runnable r) {
                    Thread ret = new Thread(r, "messaging " + num);
                    num.set(num.get() + 1);
                    ret.setDaemon(true);
                    return ret;
                }
            });
        }

        decouplePool = new ScheduledThreadPoolExecutor(1);
        //noinspection unused,unused
        decouplePool.setThreadFactory(new ThreadFactory() {
            private AtomicInteger num = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread ret = new Thread(r, "decouple_thr_" + num);
                num.set(num.get() + 1);
                ret.setDaemon(true);
                return ret;
            }
        });
        morphium.addShutdownListener(this);

        this.queueName = queueName;
        running = true;
        this.pause = pause;
        this.processMultiple = processMultiple;
        id = UUID.randomUUID().toString();
        hostname = System.getenv("HOSTNAME");
        if (hostname == null) {
            try {
                hostname = InetAddress.getLocalHost().getHostName();
            } catch (UnknownHostException ignored) {
            }
        }
        if (hostname == null) {
            hostname = "unknown host";
        }

        m.ensureIndicesFor(Msg.class, getCollectionName());
        //        try {
        //            m.ensureIndex(Msg.class, Msg.Fields.lockedBy, Msg.Fields.timestamp);
        //            m.ensureIndex(Msg.class, Msg.Fields.lockedBy, Msg.Fields.processedBy);
        //            m.ensureIndex(Msg.class, Msg.Fields.timestamp);
        //        } catch (Exception e) {
        //            log.error("Could not ensure indices", e);
        //        }

        listeners = new CopyOnWriteArrayList<>();
        listenerByName = new HashMap<>();
    }

    public long getMessageCount() {
        return morphium.createQueryFor(Msg.class).setCollectionName(getCollectionName()).countAll();
    }

    public void removeMessage(Msg m) {
        morphium.delete(m, getCollectionName());
    }

    public List<Msg> findMessages(Query<Msg> q) {
        try {
            q = q.clone();
        } catch (CloneNotSupportedException e) {
            //cannot happen
        }
        q.setCollectionName(getCollectionName());
        return q.asList();
    }

    @Override
    public void run() {
        setName("Msg " + id);
        if (log.isDebugEnabled()) {
            log.debug("Messaging " + id + " started");
        }


        if (useChangeStream) {
            log.debug("Before running the changestream monitor - check of already existing messages");
            try {
                findAndProcessPendingMessages(null);
                if (multithreadded) {
                    //wait for preprocessing to finish
                    while (threadPool.getActiveCount() > 0) {
                        Thread.yield();
                    }
                }
            } catch (Exception e) {
                log.error("Error processing existing messages in queue", e);
            }
            log.debug("init Messaging  using changestream monitor");
            //changeStreamMonitor = new changeStream(morphium, getCollectionName(), false);
            changeStreamMonitor = new ChangeStreamMonitor(morphium, getCollectionName(), true);
            changeStreamMonitor.addListener(evt -> {
//                    log.debug("incoming message via changeStream");
                try {
                    if (evt == null || evt.getOperationType() == null) return running;
                    if (evt.getOperationType().equals("insert")) {
                        //insert => new Message
                        //                        log.debug("New message incoming");
                        Msg obj = morphium.getMapper().deserialize(Msg.class, evt.getFullDocument());
                        if (obj.getSender().equals(id) || (obj.getProcessedBy() != null && obj.getProcessedBy().contains(id)) || (obj.getRecipient() != null && !obj.getRecipient().equals(id))) {
                            //ignoring my own messages
                            return running;
                        }
                        if (pauseMessages.containsKey(obj.getName())) {
                            log.debug("Not processing message - processing paused for " + obj.getName());
                            return running;
                        }
                        //do not process messages, that are exclusive, but already processed or not for me / all
                        if (obj.isExclusive() && obj.getLockedBy() == null && (obj.getRecipient() == null || obj.getRecipient().equals(id)) && (obj.getProcessedBy() == null || !obj.getProcessedBy().contains(id))) {
                            // locking
                            log.debug("trying to lock exclusive message");
                            lockAndProcess(obj);

                        } else if (!obj.isExclusive() || (obj.getRecipient() != null && obj.getRecipient().equals(id))) {
                            //I need process this new message... it is either for all or for me directly
                            if (processing.contains(obj.getMsgId())) {
                                return running;
                            }
                            //                        obj = morphium.reread(obj);
                            //                        if (obj.getReceivedBy() != null && obj.getReceivedBy().contains(id)) {
                            //                            //already got this message - is still being processed it seems
                            //                            return;
                            //                        }
                            List<Msg> lst = new ArrayList<>();
                            lst.add(obj);

                            try {
                                processMessages(lst);
                            } catch (Exception e) {
                                log.error("Error during message processing ", e);
                            }
                        } else {
                            log.debug("Message is not for me");
                        }

                    } else if (evt.getOperationType().equals("update")) {
                        //dealing with updates... i could have "lost" a lock
                        //                        if (((Map<String,Object>)data.get("o")).get("$set")!=null){
                        //                            //there is a set-update
                        //                        }
                        if (evt.getFullDocument() != null && evt.getFullDocument().get("_id") != null) {
                            Msg obj = morphium.findById(Msg.class, new MorphiumId(evt.getFullDocument().get("_id").toString()), getCollectionName());
                            if (obj != null && obj.isExclusive() && obj.getLockedBy() == null && !pauseMessages.containsKey(obj.getName()) && (obj.getRecipient() == null || obj.getRecipient().equals(id))) {
                                log.debug("Update of msg - trying to lock");
                                // locking
                                lockAndProcess(obj);
                            }
                        }
                        //if msg was not exclusive, we should have processed it on insert

                    }
                } catch (Exception e) {
                    log.error("Error during event processing in changestream", e);
                }
                return running;
            });
            changeStreamMonitor.start();
        } else {

            while (running) {
                try {
                    findAndProcessMessages(processMultiple);
                } catch (Throwable e) {
                    log.error("Unhandled exception " + e.getMessage(), e);
                } finally {
                    try {
                        sleep(pause);
                    } catch (InterruptedException ignored) {
                    }
                }


            }

            if (log.isDebugEnabled()) {
                log.debug("Messaging " + id + " stopped!");
            }
            if (!running) {
                listeners.clear();
                listenerByName.clear();
            }
        }
    }

    /**
     * pause processing for certain messages
     *
     * @param name
     */

    public void pauseProcessingOfMessagesNamed(String name) {
        pauseMessages.putIfAbsent(name, System.currentTimeMillis());
    }

    /**
     * unpause processing
     *
     * @param name
     * @return duration or null
     */
    public Long unpauseProcessingOfMessagesNamed(String name) {
        MorphiumIterator<Msg> messages = findMessages(name, processMultiple);
        processMessages(messages);
        Long ret = pauseMessages.remove(name);
        if (ret != null) {
            ret = System.currentTimeMillis() - ret;
        }
        //decouplePool.execute(r);

        return ret;
    }

    public void findAndProcessPendingMessages(String name) {
        Runnable r = () -> {
            while (true) {
                MorphiumIterator<Msg> messages = findMessages(name, processMultiple);
                if (!messages.hasNext()) break;
                processMessages(messages);
                try {
                    Thread.sleep(pause);
                } catch (InterruptedException e) {
                    //Swallow
                }
            }
        };
        queueOrRun(r);

    }

    private MorphiumIterator<Msg> findMessages(String name, boolean multiple) {
        Map<String, Object> values = new HashMap<>();
        Query<Msg> q = morphium.createQueryFor(Msg.class);
        q.setCollectionName(getCollectionName());

        //locking messages...
        Query<Msg> or1 = q.q().f(Msg.Fields.sender).ne(id).f(Msg.Fields.lockedBy).eq(null).f(Msg.Fields.processedBy).ne(id).f(Msg.Fields.recipient).eq(null);
        Query<Msg> or2 = q.q().f(Msg.Fields.sender).ne(id).f(Msg.Fields.processedBy).ne(id).f(Msg.Fields.recipient).eq(id);
        Set<String> pausedMessagesKeys = pauseMessages.keySet();
        if (name != null) {
            or1.f(Msg.Fields.name).eq(name);
            or2.f(Msg.Fields.name).eq(name);
        } else {
            //not searching for paused messages
            if (!pauseMessages.isEmpty()) {
                or1.f(Msg.Fields.name).nin(pausedMessagesKeys);
                or2.f(Msg.Fields.name).nin(pausedMessagesKeys);
            }
        }
        ArrayList<MorphiumId> processingIds = new ArrayList<>(processing);
        if (!processing.isEmpty()) {
            q.f("_id").nin(processingIds);
        }
        q.or(or1, or2);
        q.sort(Msg.Fields.priority, Msg.Fields.timestamp);
        if (!multiple) {
            q.limit(1);
        }

        values.put("locked_by", id);
        values.put("locked", System.currentTimeMillis());
        morphium.set(q.q().f("_id").in(q.idList()), values, false, multiple);
        q = q.q();
        if (name != null) {
            q.f(Msg.Fields.name).eq(name);
        } else {
            //not searching for paused messages
            if (!pauseMessages.isEmpty()) {
                q.f(Msg.Fields.name).nin(pausedMessagesKeys);
            }
        }
        q.f("_id").nin(processingIds);
        if (name != null) {
            q.f(Msg.Fields.name).eq(name);
        } else {
            //not searching for paused messages
            if (!pauseMessages.isEmpty()) {
                q.f(Msg.Fields.name).nin(pausedMessagesKeys);
            }
        }
        q.or(q.q().f(Msg.Fields.lockedBy).eq(id),
                q.q().f(Msg.Fields.lockedBy).eq("ALL").f(Msg.Fields.processedBy).ne(id).f(Msg.Fields.recipient).eq(id),
                q.q().f(Msg.Fields.lockedBy).eq("ALL").f(Msg.Fields.processedBy).ne(id).f(Msg.Fields.recipient).eq(null));
        q.sort(Msg.Fields.priority, Msg.Fields.timestamp);
        if (!multiple) {
            q.limit(1);
        }
        //                List<Msg> messages = q.asList();
        MorphiumIterator<Msg> it = q.asIterable(windowSize);
        it.setMultithreaddedAccess(multithreadded);
        return it;
    }

    private void findAndProcessMessages(boolean multiple) {
        MorphiumIterator<Msg> messages = findMessages(null, multiple);
        processMessages(messages);
    }

    private void lockAndProcess(Msg obj) {
        Query<Msg> q = morphium.createQueryFor(Msg.class);
        q.setCollectionName(getCollectionName());
        q.f("_id").eq(obj.getMsgId());
        q.f(Msg.Fields.processedBy).ne(id);
        Map<String, Object> values = new HashMap<>();
        values.put("locked_by", id);
        values.put("locked", System.currentTimeMillis());
        morphium.set(q, values, false, processMultiple);
        try {
            //wait for the locking to be saved
            Thread.sleep(10);
        } catch (InterruptedException e) {
            //swallow
        }
        obj = morphium.reread(obj, getCollectionName());
        if (obj != null && obj.getLockedBy() != null && obj.getLockedBy().equals(id)) {
            List<Msg> lst = new ArrayList<>();
            lst.add(obj);
            log.debug("locked messages: "+lst.size());
            try {
                processMessages(lst);
            } catch (Exception e) {
                log.error("Error during message processing ", e);
            }
        }
    }

    private void processMessages(Iterable<Msg> messages) {
//        final List<Msg> toStore = new ArrayList<>();
//        final List<Runnable> toExec = new ArrayList<>();
        for (final Msg m : messages) {
            if (m == null) {
                continue; //message was erased
            }
            final Msg msg = morphium.reread(m, getCollectionName()); //make sure it's current version in DB
            if (msg == null) continue;

            //noinspection SuspiciousMethodCalls
            if (msg.getInAnswerTo() != null && waitingForMessages.get(msg.getInAnswerTo()) != null) {
                log.debug("Got a message, we are waiting for...");
                //this message we were waiting for
                waitingForAnswers.put((MorphiumId) msg.getInAnswerTo(), msg);
                processing.remove(m.getMsgId());
                morphium.delete(msg, getCollectionName());
                return;
            }
            if (listeners.isEmpty() && listenerByName.isEmpty()) {
                updateProcessedByAndReleaseLock(msg);
                log.debug("no listener registered... not processing message");
                return;
            }
//            Query<? extends Msg> q = morphium.createQueryFor(m.getClass()).f("_id").eq(m.getMsgId());
//            q.setCollectionName(getCollectionName());
//            morphium.push(q, Msg.Fields.receivedBy, id);
            if (processing.contains(m.getMsgId())) continue;
            processing.add(m.getMsgId());
//            log.info("Adding msg " + m.getMsgId());
            Runnable r = () -> {
                if (m.getProcessedBy() != null && m.getProcessedBy().contains(id)) {
                    //log.error("Was already processed?");
                    //throw new RuntimeException("was already processed - error on mongo query result!");
                    return;
                }

                if (msg == null) {
                    processing.remove(m.getMsgId());
                    return; //was deleted
                }

                if (msg.getProcessedBy() != null && msg.getProcessedBy().contains(id)) {
//                    log.debug("Was already processed - multithreadding?");
                    processing.remove(m.getMsgId());
                    return;
                }
                if (!msg.getLockedBy().equals(id) && !msg.getLockedBy().equals("ALL")) {
                    //over-locked by someone else
                    processing.remove(m.getMsgId());
                    return;
                }

                if (msg.getTtl() < System.currentTimeMillis() - msg.getTimestamp()) {
                    //Delete outdated msg!
                    log.debug("Found outdated message - deleting it!");
                    morphium.delete(msg, getCollectionName());
                    processing.remove(m.getMsgId());
                    return;
                }
                boolean wasProcessed = false;
                boolean wasRejected = false;
                List<MessageRejectedException> rejections = new ArrayList<>();
                List<MessageListener> lst = new ArrayList<>(listeners);
                if (listenerByName.get(msg.getName()) != null) {
                    lst.addAll(listenerByName.get(msg.getName()));
                }
                if (lst.isEmpty()) {
                    log.debug("Message did not have a listener registered");
                    wasProcessed = true;
                }
                for (MessageListener l : lst) {
                    try {
                        Msg answer = l.onMessage(Messaging.this, msg);
                        wasProcessed = true;
                        if (autoAnswer && answer == null) {
                            answer = new Msg(msg.getName(), "received", "");
                        }
                        if (answer != null) {
                            msg.sendAnswer(Messaging.this, answer);
                        }
                    } catch (MessageRejectedException mre) {
                        log.warn("Message was rejected by listener", mre);
                        wasRejected = true;
                        rejections.add(mre);
                    } catch (Exception e) {
                        log.error("listener Processing failed", e);
                    }
                }


                if (wasRejected) {
                    for (MessageRejectedException mre : rejections) {
                        if (mre.isSendAnswer()) {
                            Msg answer = new Msg(msg.getName(), "message rejected by listener", mre.getMessage());
                            msg.sendAnswer(Messaging.this, answer);

                        }
                        if (mre.isContinueProcessing()) {
                            updateProcessedByAndReleaseLock(msg);
                            processing.remove(m.getMsgId());
                            log.info("Message will be re-processed by others");
                        }
                    }
                }
                if (!wasProcessed) {
                    //                        msg.addAdditional("Processing of message failed by "+getSenderId()+": "+t.getMessage());
                    log.error("message was not processed");
                }

                //                            if (msg.getType().equals(MsgType.SINGLE)) {
                //                                //removing it
                //                                morphium.delete(msg, getCollectionName());
                //                            }
                //updating it to be processed by others...
                if ((msg.getLockedBy() != null && msg.getLockedBy().equals("ALL")) || (msg.getRecipient()!=null && msg.getRecipient().equals(id) && msg.getInAnswerTo() != null)) {
                    updateProcessedByAndReleaseLock(msg);
                } else {
                    //Exclusive message
                    morphium.delete(msg,getCollectionName());
                    //                                msg.addProcessedId(id);
                    //                                msg.setLockedBy(null);
                    //                                msg.setLocked(0);
                    //                                toStore.add(msg);
                }
                Runnable rb = () -> {
                    processing.remove(m.getMsgId());
//                    log.debug("Removed from processing " + m.getMsgId());
                };
                while (true) {
                    try {
                        if (!decouplePool.isTerminated() && !decouplePool.isTerminating() && !decouplePool.isShutdown()) {
                            decouplePool.schedule(rb, m.getTtl(), TimeUnit.MILLISECONDS); //avoid re-executing message
                        }
                        break;
                    } catch (RejectedExecutionException ex) {
                        try {
                            Thread.sleep(pause);
                        } catch (InterruptedException e) {
                            //Swallow
                        }
                    }
                }
            };


            queueOrRun(r);

        }

        //wait for all threads to finish
//        if (multithreadded) {
//            while (threadPool != null && threadPool.getActiveCount() > 0) {
//                Thread.yield();
//            }
//        }
//        morphium.storeList(toStore, getCollectionName());
//        morphium.delete(toRemove, getCollectionName());
//        toExec.forEach(this::queueOrRun);
        while (morphium.getWriteBufferCount() > 0) {
            Thread.yield();
        }
    }


    private void updateProcessedByAndReleaseLock(Msg msg) {
        Query<Msg> idq = morphium.createQueryFor(Msg.class);
        idq.setCollectionName(getCollectionName());
        idq.f(Msg.Fields.msgId).eq(msg.getMsgId());
        if (msg.getLockedBy().equals(id)) {
            //releasing lock
            morphium.set(idq, Msg.Fields.lockedBy, null);
        }
        morphium.push(idq, Msg.Fields.processedBy, id);
    }

    private void queueOrRun(Runnable r) {
        if (multithreadded) {
            boolean queued = false;
            while (!queued) {
                try {
                    threadPool.execute(r);
                    queued = true;
                } catch (Throwable ignored) {
                }
            }
            //throtteling to windowSize - do not create more threads than windowSize
            while (threadPool.getActiveCount() > windowSize) {
                Thread.yield();
            }
        } else {
            r.run();
        }
    }

    public String getCollectionName() {
        if (queueName == null || queueName.isEmpty()) {
            return "msg";
        }
        return "mmsg_" + queueName;

    }

    public void addListenerForMessageNamed(String n, MessageListener l) {
        if (listenerByName.get(n) == null) {
            HashMap<String, List<MessageListener>> c = (HashMap) ((HashMap) listenerByName).clone();
            c.put(n, new ArrayList<>());
            listenerByName = c;
        }
        listenerByName.get(n).add(l);
    }

    public void removeListenerForMessageNamed(String n, MessageListener l) {
        //        l.setMessaging(null);
        if (listenerByName.get(n) == null) {
            return;
        }
        HashMap<String, List<MessageListener>> c = (HashMap) ((HashMap) listenerByName).clone();
        c.get(n).remove(l);
        listenerByName = c;

    }

    public String getSenderId() {
        return id;
    }

    public Messaging setSenderId(String id) {
        this.id = id;
        return this;
    }

    public int getPause() {
        return pause;
    }

    public Messaging setPause(int pause) {
        this.pause = pause;
        return this;
    }

    public boolean isRunning() {
        if (useChangeStream) {
            return changeStreamMonitor != null && changeStreamMonitor.isRunning();
        }
        return running;
    }

    @Deprecated
    public void setRunning(boolean running) {
        if (!running && (changeStreamMonitor != null)) changeStreamMonitor.stop();
        this.running = running;
    }

    public void terminate() {
        running = false;
        if (decouplePool != null) {
            int sz = decouplePool.shutdownNow().size();
            log.debug("Shutting down with " + sz + " runnables still scheduled");
        }
        if (threadPool != null) {
            int sz = threadPool.shutdownNow().size();
            log.debug("Shutting down with " + sz + " runnables still pending in pool");
        }
        if (changeStreamMonitor != null) changeStreamMonitor.stop();
        sendMessageToSelf(new Msg("info", "going down", "now"));
        if (isAlive()) {
            interrupt();
        }
        if (isAlive()) {
            //noinspection deprecation
            stop();
        }
    }

    public void addMessageListener(MessageListener l) {
        listeners.add(l);
    }

    public void removeMessageListener(MessageListener l) {
        listeners.remove(l);
    }

    public void queueMessage(final Msg m) {
        storeMsg(m, true);
    }


    @Override
    public synchronized void start() {
        super.start();
        if (useChangeStream) {
            try {
                Thread.sleep(250);
                //wait for changestream to kick in ;-)
            } catch (Exception e) {
                log.error("error:" + e.getMessage());
            }
        }
    }

    public void storeMessage(Msg m) {
        storeMsg(m, false);
    }

    public long getNumberOfMessages() {
        Query<Msg> q = morphium.createQueryFor(Msg.class);
        q.setCollectionName(getCollectionName());
        return q.countAll();
    }

    private void storeMsg(Msg m, boolean async) {
        AsyncOperationCallback cb = null;
        if (async) {
            //noinspection unused,unused
            cb = new AsyncOperationCallback() {
                @Override
                public void onOperationSucceeded(AsyncOperationType type, Query q, long duration, List result, Object entity, Object... param) {
                }

                @Override
                public void onOperationError(AsyncOperationType type, Query q, long duration, String error, Throwable t, Object entity, Object... param) {
                    log.error("Error storing msg", t);
                }
            };
        }
        //m.setDeleteAt(new Date(System.currentTimeMillis() + m.getTtl()));
        m.setSender(id);
        m.addProcessedId(id);

        m.setSenderHost(hostname);
        if (m.getTo() != null && !m.getTo().isEmpty()) {
            for (String recipient : m.getTo()) {
                try {
                    Msg msg = m.getClass().getDeclaredConstructor().newInstance();
                    List<Field> flds = morphium.getARHelper().getAllFields(m.getClass());
                    for (Field f : flds) {
                        f.setAccessible(true);
                        f.set(msg, f.get(m));
                    }
                    msg.setMsgId(null);
                    msg.setRecipient(recipient);
                    morphium.storeNoCache(msg, getCollectionName(), cb);
                } catch (Exception e) {
                    throw new RuntimeException("Sending of answer failed", e);
                }
            }
        } else {
            morphium.storeNoCache(m, getCollectionName(), cb);
        }
    }

    public void sendMessageToSelf(Msg m) {
        sendMessageToSelf(m, false);
    }

    public void queueMessagetoSelf(Msg m) {
        sendMessageToSelf(m, true);
    }

    private void sendMessageToSelf(Msg m, boolean async) {
        AsyncOperationCallback cb = null;
        //noinspection StatementWithEmptyBody
        if (async) {
            //noinspection unused,unused
        }
        m.setSender("self");
        m.setRecipient(id);
        m.setSenderHost(hostname);
        morphium.storeNoCache(m,getCollectionName());
    }

    public boolean isAutoAnswer() {
        return autoAnswer;
    }

    public Messaging setAutoAnswer(boolean autoAnswer) {
        this.autoAnswer = autoAnswer;
        return this;
    }

    @Override
    public void onShutdown(Morphium m) {
        try {
            running = false;
            if (threadPool != null) {
                threadPool.shutdown();
                Thread.sleep(200);
                if (threadPool != null) {
                    threadPool.shutdownNow();
                }
                threadPool = null;
            }
            if (changeStreamMonitor != null) changeStreamMonitor.stop();
        } catch (Exception e) {
            e.printStackTrace();
            //swallow
        }
    }

    public Msg sendAndAwaitFirstAnswer(Msg theMessage, long timeoutInMs) {
        theMessage.setMsgId(new MorphiumId());
        waitingForMessages.put(theMessage.getMsgId(), theMessage);
        storeMessage(theMessage);
        long start = System.currentTimeMillis();
        while (!waitingForAnswers.containsKey(theMessage.getMsgId())) {
            if (System.currentTimeMillis() - start > timeoutInMs) {
                log.error("Did not receive answer " + theMessage.getName() + "/" + theMessage.getMsgId() + " in time (" + timeoutInMs + "ms)");
                waitingForMessages.remove(theMessage.getMsgId());
                throw new RuntimeException("Did not receive answer for message " + theMessage.getName() + "/" + theMessage.getMsgId() + " in time (" + timeoutInMs + "ms)");
            }
            Thread.yield();
        }
        waitingForMessages.remove(theMessage.getMsgId());
        return waitingForAnswers.remove(theMessage.getMsgId());
    }

    public List<Msg> sendAndAwaitAnswers(Msg theMessage, int numberOfAnswers, long timeout) {
        List<Msg> answers = new ArrayList<>();
        storeMessage(theMessage);
        waitingForMessages.put(theMessage.getMsgId(), theMessage);
        long start = System.currentTimeMillis();
        while (true) {
            if (waitingForAnswers.get(theMessage.getMsgId()) != null) {
                answers.add(waitingForAnswers.remove(theMessage.getMsgId()));
            }
            if (numberOfAnswers >= 0 && answers.size() >= numberOfAnswers) {
                break;
            }
            if (System.currentTimeMillis() - start > timeout) {
                break;
            }
            Thread.yield();
        }
        waitingForMessages.remove(theMessage.getMsgId());
        return answers;
    }


    public boolean isProcessMultiple() {
        return processMultiple;
    }

    public Messaging setProcessMultiple(boolean processMultiple) {
        this.processMultiple = processMultiple;
        return this;
    }

    public String getQueueName() {
        return queueName;
    }

    public Messaging setQueueName(String queueName) {
        this.queueName = queueName;
        return this;
    }

    public boolean isMultithreadded() {
        return multithreadded;
    }

    public Messaging setMultithreadded(boolean multithreadded) {
        this.multithreadded = multithreadded;
        return this;
    }

    public int getWindowSize() {
        return windowSize;
    }

    public Messaging setWindowSize(int windowSize) {
        this.windowSize = windowSize;
        return this;
    }

    public boolean isUseChangeStream() {
        return useChangeStream;
    }

    public Messaging setUseChangeStream(boolean useChangeStream) {
        this.useChangeStream = useChangeStream;
        return this;
    }
}
