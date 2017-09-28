package de.caluga.test.mongo.suite;

import de.caluga.morphium.annotations.CreationTime;
import de.caluga.morphium.annotations.Entity;
import de.caluga.morphium.annotations.Id;
import org.junit.Test;

import java.util.Date;

/**
 * Created with IntelliJ IDEA.
 * User: stephan
 * Date: 19.11.13
 * Time: 12:29
 * To change this template use File | Settings | File Templates.
 */
public class CheckForNewTest extends MongoTest {

    @Test
    public void testCheckForNew() throws Exception {
        //although checkfornew is enabled, it will not update created
        //as the @CreationTime annotation disables it
        morphium.getConfig().setCheckForNew(true);
        morphium.delete(morphium.createQueryFor(TestID.class));

        TestID tst = new TestID();
        tst.theId = "1";
        tst.theValue = "value";
        morphium.store(tst);
        assert (tst.created == null);


        tst = new TestID();
        tst.theId = "2";
        tst.theValue = "value2";
        morphium.store(tst);
        assert (tst.created == null);

        tst = new TestID();
        tst.theId = "2";
        tst.theValue = "value";
        morphium.store(tst);
        assert (tst.created == null);

        tst.created = new Date();
        Date cr = tst.created;

        morphium.store(tst);
        assert (cr.equals(tst.created));

        morphium.reread(tst);
        assert (cr.equals(tst.created));

        morphium.getConfig().setCheckForNew(false);
    }


    @Test
    public void testCheckForNew2() throws Exception {
        morphium.getConfig().setCheckForNew(true);
        morphium.delete(morphium.createQueryFor(TestID2.class));

        TestID2 tst = new TestID2();
        tst.theId = "1";
        tst.theValue = "value";
        morphium.store(tst);
        assert (tst.created != null);


        tst = new TestID2();
        tst.theId = "2";
        tst.theValue = "value2";
        morphium.store(tst);
        assert (tst.created != null);

        Date cr = tst.created;

        tst = new TestID2();
        tst.theId = "2";
        tst.theValue = "value";
        morphium.store(tst);
        assert (tst.created != null);
        assert (tst.created.equals(cr));

        morphium.getConfig().setCheckForNew(false);
    }

    @Entity
    @CreationTime(checkForNew = true)
    public static class TestID2 {
        @Id
        public String theId;
        public String theValue;

        @CreationTime
        public Date created;

    }


    @Entity
    @CreationTime(checkForNew = false)
    public static class TestID {
        @Id
        public String theId;
        public String theValue;

        @CreationTime
        public Date created;

    }
}
