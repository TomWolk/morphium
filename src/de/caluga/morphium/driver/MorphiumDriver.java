package de.caluga.morphium.driver;/**
 * Created by stephan on 15.10.15.
 */

import de.caluga.morphium.driver.bulk.BulkRequestContext;
import de.caluga.morphium.driver.mongodb.Maximums;

import java.util.List;
import java.util.Map;

/**
 * TODO: Add Documentation here
 **/
public interface MorphiumDriver {

    void setCredentials(String db, String login, char[] pwd);

    String[] getCredentials(String db);

    boolean isDefaultFsync();

    String[] getHostSeed();

    int getMaxConnectionsPerHost();

    int getMinConnectionsPerHost();

    int getMaxConnectionLifetime();

    int getMaxConnectionIdleTime();

    int getSocketTimeout();

    int getConnectionTimeout();

    int getDefaultW();

    int getMaxBlockintThreadMultiplier();

    int getHeartbeatFrequency();

    void setHeartbeatSocketTimeout(int heartbeatSocketTimeout);

    void setUseSSL(boolean useSSL);

    void setHeartbeatFrequency(int heartbeatFrequency);

    void setWriteTimeout(int writeTimeout);

    void setDefaultBatchSize(int defaultBatchSize);

    void setCredentials(Map<String, String[]> credentials);

    int getHeartbeatSocketTimeout();

    boolean isUseSSL();

    boolean isDefaultJ();

    int getWriteTimeout();

    int getLocalThreshold();

    void setHostSeed(String... host);

    void setMaxConnectionsPerHost(int mx);

    void setMinConnectionsPerHost(int mx);

    void setMaxConnectionLifetime(int timeout);

    void setMaxConnectionIdleTime(int time);

    void setSocketTimeout(int timeout);

    void setConnectionTimeout(int timeout);

    void setDefaultW(int w);

    void setMaxBlockingThreadMultiplier(int m);

    void heartBeatFrequency(int t);

    void heartBeatSocketTimeout(int t);

    void useSsl(boolean ssl);

    void connect() throws MorphiumDriverException;

    void setDefaultReadPreference(ReadPreference rp);

    void connect(String replicasetName) throws MorphiumDriverException;

    ;

    Maximums getMaximums();

    boolean isConnected();

    void setDefaultJ(boolean j);

    void setDefaultWriteTimeout(int wt);

    void setLocalThreshold(int thr);

    void setDefaultFsync(boolean j);

    void setRetriesOnNetworkError(int r);

    int getRetriesOnNetworkError();

    void setSleepBetweenErrorRetries(int s);

    int getSleepBetweenErrorRetries();

    void close() throws MorphiumDriverException;

    Map<String, Object> getStats() throws MorphiumDriverException;

    Map<String, Object> getOps(long threshold) throws MorphiumDriverException;

    Map<String, Object> runCommand(String db, Map<String, Object> cmd) throws MorphiumDriverException;

    List<Map<String, Object>> find(String db, String collection, Map<String, Object> query, Map<String, Integer> sort, Map<String, Integer> projection, int skip, int limit, int batchSize, ReadPreference rp) throws MorphiumDriverException;

    long count(String db, String collection, Map<String, Object> query, ReadPreference rp) throws MorphiumDriverException;

    /**
     * just insert - no special handling
     *
     * @param db
     * @param collection
     * @param objs
     * @param wc
     * @throws MorphiumDriverException
     */
    void insert(String db, String collection, List<Map<String, Object>> objs, WriteConcern wc) throws MorphiumDriverException;

    Map<String, Object> update(String db, String collection, Map<String, Object> query, Map<String, Object> op, boolean multiple, boolean upsert, WriteConcern wc) throws MorphiumDriverException;

    Map<String, Object> delete(String db, String collection, Map<String, Object> query, boolean multiple, WriteConcern wc) throws MorphiumDriverException;

    void drop(String db, String collection, WriteConcern wc) throws MorphiumDriverException;

    void drop(String db, WriteConcern wc) throws MorphiumDriverException;

    boolean exists(String db) throws MorphiumDriverException;

    List<Object> distinct(String db, String collection, String field) throws MorphiumDriverException;

    boolean exists(String db, String collection) throws MorphiumDriverException;

    Map<String, Object> getIndexes(String db, String collection) throws MorphiumDriverException;

    List<String> getCollectionNames(String db) throws MorphiumDriverException;

    Map<String, Object> group(String db, String coll, Map<String, Object> query, Map<String, Object> initial, String jsReduce, String jsFinalize, ReadPreference rp, String... keys);

    Map<String, Object> killCursors(String db, String collection, List<Long> cursorIds) throws MorphiumDriverException;

    Map<String, Object> aggregate(String db, String collection, List<Map<String, Object>> pipeline, boolean explain, boolean allowDiskUse, ReadPreference readPreference) throws MorphiumDriverException;

    boolean isSocketKeepAlive();

    void setSocketKeepAlive(boolean socketKeepAlive);

    int getHeartbeatConnectTimeout();

    void setHeartbeatConnectTimeout(int heartbeatConnectTimeout);

    int getMaxWaitTime();

    void setMaxWaitTime(int maxWaitTime);

    boolean isCapped(String db, String coll) throws MorphiumDriverException;

    BulkRequestContext createBulkContext(String db, String collection, boolean ordered, WriteConcern wc);

    void createIndex(String db, String collection, Map<String, Object> index, Map<String, Object> options) throws MorphiumDriverException;
}
