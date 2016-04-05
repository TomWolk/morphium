package de.caluga.morphium.driver;/**
 * Created by stephan on 05.11.15.
 */

/**
 * define how secure the write should be. most important the w value which states the number of nodes written to:
 * 0: no error handling
 * 1: master only
 * >1: number of nodes
 * -1: all available replicase nodes
 * -2: majority
 **/
public class WriteConcern {
    //number of nodes data is written to
    //0: no error handling
    //1: master only
    //>1: number of nodes
    //-1: all available Replicaset Nodes
    //-2: Majority
    private int w;

    //journaled
    private boolean j;
    private boolean fsync;

    /**
     * write timeout
     */
    private int wtimeout;

    public int getW() {
        return w;
    }

    public boolean isJ() {
        return j;
    }

    public boolean isFsync() {
        return fsync;
    }

    public int getWtimeout() {
        return wtimeout;
    }

    private WriteConcern(int w, boolean fsync, boolean j, int wtimeout) {
        this.w = w;
        this.j = j;
        this.wtimeout = wtimeout;
        this.fsync = fsync;
    }

    public static WriteConcern getWc(int w, boolean fsync, boolean j, int wtimeout) {
        return new WriteConcern(w, fsync, j, wtimeout);
    }

}
