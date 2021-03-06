package de.caluga.morphium.driver.mongodb;/**
 * Created by stephan on 09.11.15.
 */

import de.caluga.morphium.MorphiumReference;
import de.caluga.morphium.driver.MorphiumDriverException;
import de.caluga.morphium.driver.MorphiumDriverNetworkException;
import de.caluga.morphium.driver.MorphiumDriverOperation;
import de.caluga.morphium.driver.MorphiumId;
import org.bson.types.ObjectId;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Array;
import java.util.*;

/**
 * helper class
 */
@SuppressWarnings("WeakerAccess")
public class DriverHelper {
    //Logger logger = LoggerFactory.getLogger(DriverHelper.class);


    public static Map<String, Object> doCall(MorphiumDriverOperation r, int maxRetry, int sleep) throws MorphiumDriverException {
        Exception lastException = null;
        for (int i = 0; i < maxRetry; i++) {
            try {
                Map<String, Object> ret = r.execute();
                if (i > 0) {
                    if (lastException == null) {
                        LoggerFactory.getLogger(DriverHelper.class).warn("recovered from error without exception");
                    } else {
                        LoggerFactory.getLogger(DriverHelper.class).warn("recovered from error: " + lastException.getMessage());
                    }
                }
                return ret;
            } catch (IllegalStateException e1){
                //should be open...
            } catch (Exception e) {
                lastException = e;
                handleNetworkError(maxRetry, i, sleep, e);
            }
        }
        return null;
    }


    private static void handleNetworkError(int max, int i, int sleep, Throwable e) throws MorphiumDriverException {
        LoggerFactory.getLogger(DriverHelper.class).info("Handling network error..." + e.getClass().getName());
        if (e.getClass().getName().equals("javax.validation.ConstraintViolationException")) {
            throw (new MorphiumDriverException("Validation error", e));
        }
        if (e.getClass().getName().contains("DuplicateKeyException")) {
            throw new MorphiumDriverException("Duplicate Key", e);
        }
        if (e.getClass().getName().contains("MongoExecutionTimeoutException")
                || e.getClass().getName().contains("MorphiumDriverNetworkException")
                || e.getClass().getName().contains("MongoTimeoutException")
                || e.getClass().getName().contains("MongoSocketReadTimeoutException")
                || e.getClass().getName().contains("MongoWaitQueueFullException")
                || e.getClass().getName().contains("MongoWriteConcernException")
                || e.getClass().getName().contains("MongoSocketReadException")
                || e.getClass().getName().contains("MongoSocketOpenException")
                || e.getClass().getName().contains("MongoSocketClosedException")
                || e.getClass().getName().contains("MongoSocketException")
                || e.getClass().getName().contains("MongoNotPrimaryException")
                || e.getClass().getName().contains("MongoInterruptedException")
                || e.getClass().getName().contains("MongoNodeIsRecoveringException")
                || e.getMessage() != null && (e.getMessage().equals("can't find a master")
                || e.getMessage().startsWith("No replica set members available in")
                || e.getMessage().equals("not talking to master and retries used up"))
                || (e.getClass().getName().contains("WriteConcernException") && e.getMessage() != null && e.getMessage().contains("not master"))
                || e.getClass().getName().contains("MongoException")) {
            if (i + 1 < max) {
                LoggerFactory.getLogger(DriverHelper.class).warn("Retry because of network error: " + e.getMessage());
                try {
                    Thread.sleep(sleep);
                } catch (InterruptedException ignored) {
                }

            } else {
                LoggerFactory.getLogger(DriverHelper.class).info("no retries left - re-throwing exception");
                throw (new MorphiumDriverNetworkException("Network error error: " + e.getMessage(), e));
            }
        } else {
            throw (new MorphiumDriverException("internal error: " + e.getMessage(), e));
        }
    }

    public static void replaceBsonValues(Object in) {
        if (in == null) {
            return;
        }

        if (in instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map) in;
            Map<String, Object> toSet = new HashMap<>();
            try {
                for (Map.Entry e : m.entrySet()) {
                    if (e.getValue() instanceof ObjectId) {
                        toSet.put((String) e.getKey(), new MorphiumId(e.getValue().toString()));
                    } else if (e.getValue() instanceof Collection) {
                        Collection v = new LinkedList();
                        for (Object o : (Collection) e.getValue()) {
                            if (o != null) {
                                if (o instanceof Map
                                        || o instanceof List
                                        || o.getClass().isArray()) {
                                    replaceBsonValues(o);
                                } else if (o instanceof MorphiumId) {
                                    o = new ObjectId(o.toString());
                                }
                            }
                            //noinspection unchecked
                            v.add(o);
                        }
                        toSet.put((String) e.getKey(), v);
                    } else {
                        Object value = e.getValue();
                        replaceBsonValues(value);
                        toSet.put(String.valueOf(e.getKey()), value);
                    }
                }
                for (Map.Entry<String, Object> e : toSet.entrySet()) {
                    //noinspection unchecked
                    ((Map) in).put(e.getKey(), e.getValue());
                }

            } catch (Exception e) {
                LoggerFactory.getLogger(DriverHelper.class).error("Error replacing mongoid", e);
                //                throw new RuntimeException(e);
            }
        } else if (in instanceof Collection) {
            Collection c = (Collection) in;
            //noinspection unchecked
            c.forEach(DriverHelper::replaceBsonValues);
        } else if (in.getClass().isArray()) {

            for (int i = 0; i < Array.getLength(in); i++) {
                replaceBsonValues(Array.get(in, i));
            }
        }

    }

    public static void replaceMorphiumIdByObjectId(Object in) {
        if (in == null) {
            return;
        }
        if (in instanceof Map) {
            @SuppressWarnings("unchecked") Map<String, Object> m = (Map) in;
            Map<String, Object> toSet = new HashMap<>();
            try {
                for (Map.Entry e : m.entrySet()) {
                    if (e.getValue() instanceof MorphiumId) {
                        toSet.put((String) e.getKey(), new ObjectId(e.getValue().toString()));
                    } else if (e.getValue() instanceof MorphiumReference) {
                        toSet.put((String) e.getKey(), new ObjectId(((MorphiumReference) e.getValue()).getId().toString()));
                    } else if (e.getValue() instanceof Collection) {
                        Collection v = new LinkedList();
                        for (Object o : (Collection) e.getValue()) {
                            if (o != null) {
                                if (o instanceof Map
                                        || o instanceof List
                                        || o.getClass().isArray()) {
                                    replaceMorphiumIdByObjectId(o);
                                } else if (o instanceof MorphiumId) {
                                    o = new ObjectId(o.toString());
                                }
                            }
                            //noinspection unchecked
                            v.add(o);
                        }
                        toSet.put((String) e.getKey(), v);
                    } else {
                        Object value = e.getValue();
                        replaceMorphiumIdByObjectId(value);
                        toSet.put(String.valueOf(e.getKey()), value);
                    }
                }
                for (Map.Entry<String, Object> e : toSet.entrySet()) {
                    //noinspection unchecked
                    ((Map) in).put(e.getKey(), e.getValue());
                }

            } catch (Exception e) {
                LoggerFactory.getLogger(DriverHelper.class).error("Error replacing mongoid", e);
                //                throw new RuntimeException(e);
            }
        } else if (in instanceof Collection) {
            Collection c = (Collection) in;
            //noinspection unchecked
            c.forEach(DriverHelper::replaceMorphiumIdByObjectId);
        } else if (in.getClass().isArray()) {

            for (int i = 0; i < Array.getLength(in); i++) {
                replaceMorphiumIdByObjectId(Array.get(in, i));
            }
        }
    }
}
