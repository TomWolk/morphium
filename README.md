morphium
========

Morphium - Java Object Mapper and Caching Layer for MongoDB

Morphium is a POJO Object mapper for Accessing Mongodb. Some of the main Features of Morphium:
- actively developed
- used by a number of projects (including holidayinsider.com and holidays.hrs.de/)
- Transparent access to MongoDB
- Transparent declarative (Annotation based) caching
- Annotation based definition of mappings
- Cluster Awareness
- Cache synchronization between cluster nodes
- Asynchronous and Buffered write access
- Messaging
- fluent interface for querying mongodb
- support for the MongoDB aggregator framework
- support for complex queries
- support inheritance and polymorphism
- support for javax.validation annotations
- lifecycle method of pojos
- nearly every aspect of Morphium can be replaced by own implementation (e.g. Query-Object, CacheImplementation...)
- ConfigManager helps storing app configurations in Mongo with efficient access to it (cached)
- Support for References, including lazy loaded references
- Support for partial updated objects (when writing, only the changes of the object are transferred)



Quick Start
===========

before accessing mongo via Morphium, you need to configure Morphium. this is done by preparing a MorphiumConfig Object:
```java
  MorphiumConfig cfg = new MorphiumConfig();
  cfg.setDatabase("testdb");
  cfg.addHost("localhost", 27017);
```

you can also configure Morphium using properties: new MorphiumConfig(properties); or a json-String: MorphiumConfig cfg = MorphiumConfig.createFromJson(json);

After that, you just need to instantiate Morphium:

```
  Morphium m=new Morphium(cfg);
```

then you are good to go:

```java
  Query<MyEntity> q=m.createQueryFor(MyEntity.class).f("a_field").eq("a value");
  List<MyEntity> lst=q.asList();
  MyEntity ent=q.get();
  ...
  m.store(ent);
```

Defining an Entity is quite simple as well:
```java
  @Entity(translateCamelCase = true)
  @Cache
  public class MyEntity {
    @Id
    private ObjectId myId; 
  
    private String aField;
  
    private EmbeddedObject emb;
  
    @Reference
    private MyEntity otherEntity;
  
   ...
  }

  @Embedded
  public class EmbeddedObject {
   ...
  }
```

All entities need to have an @Id field. If the type is org.bson.types.ObjectId, it will be created by mongo, if not - you need to take care of that.
References only work to other entities (of course).
You can also use Maps, Lists or Arrays, all may also include other Entities or Embedded types.

## Use enum instead of strings for queries
As using strings to query your object might be a bit error prone, you also can use enums instead of field name strings:
```java
   Query<MyEntity> q=m.createQueryFor(MyEntity.class).f(MyEntity.Fields.aField).eq("a value");
```
of course, these enums need to be created. have a look at https://github.com/sboesebeck/intelliJGenPropertyEnumsPlugin for a plugin for generating those automatically
in our example, the result would look like this:
```java
  @Entity(translateCamelCase = true)
  @Cache
  public class MyEntity {
    @Id
    private ObjectId myId; 
  
    private String aField;
  
    private EmbeddedObject emb;
  
    @Reference
    private MyEntity otherEntity;
    ...
    public enum Fields { myId, aField, emb, otherEntity }
   
  }

  @Embedded
  public class EmbeddedObject {
   ...
  }
```


This is a very short glance at all the features of Morphium!

For more information take a closer look at the wiki.

Have fun,

Stephan
