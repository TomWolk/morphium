/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package de.caluga.test.mongo.suite;

import de.caluga.morphium.annotations.Entity;
import de.caluga.morphium.annotations.Id;
import de.caluga.morphium.annotations.Index;
import de.caluga.morphium.annotations.caching.Cache;
import org.bson.types.ObjectId;

/**
 * @author stephan
 */
@Cache(clearOnWrite = true, maxEntries = 20000, readCache = true, writeCache = true, strategy = Cache.ClearStrategy.LRU, syncCache = Cache.SyncCacheStrategy.CLEAR_TYPE_CACHE, timeout = 5000)
@Entity

public class CachedObject {
    @Index
    private String value;
    @Index
    private int counter;

    @Id
    private ObjectId id;

    public int getCounter() {
        return counter;
    }

    public ObjectId getId() {
        return id;
    }

    public void setId(ObjectId id) {
        this.id = id;
    }

    public void setCounter(int counter) {
        this.counter = counter;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }


    public String toString() {
        return "Counter: " + counter + " Value: " + value + " MongoId: " + id;
    }


}
