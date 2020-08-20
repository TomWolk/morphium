package de.caluga.morphium.aggregation;

import de.caluga.morphium.Morphium;
import de.caluga.morphium.Utils;
import de.caluga.morphium.async.AsyncOperationCallback;
import de.caluga.morphium.async.AsyncOperationType;
import de.caluga.morphium.query.Query;

import java.util.*;

/**
 * User: Stephan Bösebeck
 * Date: 30.08.12
 * Time: 16:24
 * <p/>
 */
public class AggregatorImpl<T, R> implements Aggregator<T, R> {
    private final List<Map<String, Object>> params = new ArrayList<>();
    private final List<Group<T, R>> groups = new ArrayList<>();
    private Class<? extends T> type;
    private Morphium morphium;
    private Class<? extends R> rType;
    private String collectionName;
    private boolean useDisk = false;
    private boolean explain = false;

    @Override
    public boolean isUseDisk() {
        return useDisk;
    }

    @Override
    public void setUseDisk(boolean useDisk) {
        this.useDisk = useDisk;
    }

    @Override
    public boolean isExplain() {
        return explain;
    }

    @Override
    public void setExplain(boolean explain) {
        this.explain = explain;
    }

    @Override
    public Morphium getMorphium() {
        return morphium;
    }

    @Override
    public void setMorphium(Morphium m) {
        morphium = m;
    }

    @Override
    public Class<? extends T> getSearchType() {
        return type;
    }

    @Override
    public void setSearchType(Class<? extends T> type) {
        this.type = type;
    }

    @Override
    public Class<? extends R> getResultType() {
        return rType;
    }

    @Override
    public void setResultType(Class<? extends R> type) {
        rType = type;
    }

    @Override
    public Aggregator<T, R> project(Map<String, Object> m) {
        Map<String, Object> p = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (e.getValue() instanceof Expr) {
                p.put(e.getKey(), ((Expr) e.getValue()).toQueryObject());
            } else {
                p.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> map = Utils.getMap("$project", p);

        params.add(map);
        return this;
    }

    @Override
    public Aggregator<T, R> project(String fld, Expr e) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put(fld, e.toQueryObject());
        return project(map);
    }

    @Override
    public Aggregator<T, R> project(String... m) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (String sm : m) {
            map.put(sm, 1);
        }
        return project(map);
    }
//
//    @Override
//    public Aggregator<T, R> project(Map<String, Object> m) {
//        Map<String, Object> o = Utils.getMap("$project", m);
//        params.add(o);
//        return this;
//    }

    @Override
    public Aggregator<T, R> addFields(Map<String, Object> m) {
        Map<String, Object> o = Utils.getMap("$addFields", m);
        params.add(o);
        return this;
    }

    @Override
    public Aggregator<T, R> match(Query<T> q) {
        Map<String, Object> o = Utils.getMap("$match", q.toQueryObject());
        if (collectionName == null)
            collectionName = q.getCollectionName();
        params.add(o);
        return this;
    }

    @Override
    public Aggregator<T, R> match(Expr q) {
        params.add(Utils.getMap("$match", Utils.getMap("$expr", q.toQueryObject())));
        return this;
    }

    @Override
    public Aggregator<T, R> limit(int num) {
        Map<String, Object> o = Utils.getMap("$limit", num);
        params.add(o);
        return this;
    }

    @Override
    public Aggregator<T, R> skip(int num) {
        Map<String, Object> o = Utils.getMap("$skip", num);
        params.add(o);
        return this;
    }

    @Override
    public Aggregator<T, R> unwind(String listField) {
        Map<String, Object> o = Utils.getMap("$unwind", listField);
        params.add(o);
        return this;
    }

    @Override
    public Aggregator<T, R> sort(String... prefixed) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String i : prefixed) {
            String fld = i;
            int val = 1;
            if (i.startsWith("-")) {
                fld = i.substring(1);
                val = -1;
            } else if (i.startsWith("+")) {
                fld = i.substring(1);
                val = 1;
            }
            if (i.startsWith("$")) {
                fld = fld.substring(1);
                if (!fld.contains(".")) {
                    fld = morphium.getARHelper().getFieldName(type, fld);
                }
            }
            m.put(fld, val);
        }
        sort(m);
        return this;
    }

    @Override
    public Aggregator<T, R> sort(Map<String, Integer> sort) {
        Map<String, Object> o = Utils.getMap("$sort", sort);
        params.add(o);
        return this;
    }

    @Override
    public String getCollectionName() {
        if (collectionName == null) {
            collectionName = morphium.getMapper().getCollectionName(type);
        }
        return collectionName;
    }

    @Override
    public void setCollectionName(String cn) {
        collectionName = cn;
    }

    @Override
    public Group<T, R> group(Map<String, Object> id) {
        return new Group<>(this, id);
    }

    @Override
    public Group<T, R> group(Expr id) {
        return new Group<>(this, id);

    }

    @Override
    public Group<T, R> group(String id) {
        Group<T, R> gr = new Group<>(this, id);
        groups.add(gr);
        return gr;
    }

    @Override
    public void addOperator(Map<String, Object> o) {
        params.add(o);
    }

    @Override
    public List<R> aggregate() {
        return morphium.aggregate(this);
    }

    @Override
    public void aggregate(final AsyncOperationCallback<R> callback) {
        if (callback == null) {
            morphium.aggregate(this);
        } else {

            morphium.queueTask(() -> {
                long start = System.currentTimeMillis();
                List<R> ret = morphium.aggregate(AggregatorImpl.this);
                callback.onOperationSucceeded(AsyncOperationType.READ, null, System.currentTimeMillis() - start, ret, null, AggregatorImpl.this);
            });
        }
    }

    @Override
    public List<Map<String, Object>> toAggregationList() {
        for (Group<T, R> g : groups) {
            g.end();
        }
        groups.clear();
        return params;
    }

    @Override
    public Aggregator<T, R> count(String fld) {
        params.add(Utils.getMap("$count", fld));
        return this;
    }

    @Override
    public Aggregator<T, R> count(Enum fld) {
        return count(fld.name());
    }


    /**
     * Categorizes incoming documents into groups, called buckets, based on a specified expression and
     * bucket boundaries and outputs a document per each bucket. Each output document contains an _id field
     * whose value specifies the inclusive lower bound of the bucket. The output option specifies
     * the fields included in each output document.
     * <p>
     * $bucket only produces output documents for buckets that contain at least one input document.
     *
     * @param groupBy:    Expression to group by, usually a field name
     * @param boundaries: Boundaries for the  buckets
     * @param preset:     the default, needs to be a literal
     * @param output:     definition of output documents and accumulator
     * @return
     */
    @Override
    public Aggregator<T, R> bucket(Expr groupBy, List<Expr> boundaries, Expr preset, Map<String, Expr> output) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Expr> e : output.entrySet()) {
            out.put(e.getKey(), e.getValue().toQueryObject());
        }
        List<Object> bn = new ArrayList<>();
        boundaries.stream().forEach(x -> bn.add(x.toQueryObject()));
        Map<String, Object> m = Utils.getMap("bucket", Utils.getMap("groupBy", groupBy.toQueryObject())
                .add("boudaries", bn)
                .add("default", preset)
                .add("output", out)
        );
        params.add(m);
        return this;
    }

    @Override
    public Aggregator<T, R> bucketAuto(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> collStats(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> currentOp(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> facet(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> geoNear(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> graphLookup(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> indexStats(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> listLocalSessions(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> listSessions(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> lookup(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> match(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> merge(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> out(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> planCacheStats(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> redact(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> replaceRoot(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> replaceWith(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> sample(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> set(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> sortByCount(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> unionWith(Map<String, Object> param) {
        return null;
    }

    @Override
    public Aggregator<T, R> unset(List<String> field) {
        return null;
    }

    @Override
    public Aggregator<T, R> unset(String... param) {
        return null;
    }

    @Override
    public Aggregator<T, R> unset(Enum... field) {
        return null;
    }

    @Override
    public Aggregator<T, R> genericStage(String stageName, Object param) {
        return null;
    }
}
