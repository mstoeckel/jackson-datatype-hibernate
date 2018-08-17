package com.fasterxml.jackson.datatype.hibernate5;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.persistence.ElementCollection;
import javax.persistence.EntityManager;
import javax.persistence.FetchType;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.collection.internal.PersistentArrayHolder;
import org.hibernate.collection.internal.PersistentBag;
import org.hibernate.collection.internal.PersistentIdentifierBag;
import org.hibernate.collection.internal.PersistentList;
import org.hibernate.collection.internal.PersistentMap;
import org.hibernate.collection.internal.PersistentSet;
import org.hibernate.collection.spi.PersistentCollection;
import org.hibernate.engine.spi.Mapping;
import org.hibernate.mapping.Bag;
import org.hibernate.proxy.HibernateProxy;
import org.hibernate.proxy.LazyInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonTypeInfo.Id;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.BeanProperty;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsonFormatVisitors.JsonFormatVisitorWrapper;
import com.fasterxml.jackson.databind.jsontype.TypeSerializer;
import com.fasterxml.jackson.databind.ser.ContainerSerializer;
import com.fasterxml.jackson.databind.ser.ContextualSerializer;
import com.fasterxml.jackson.databind.ser.ResolvableSerializer;
import com.fasterxml.jackson.databind.util.NameTransformer;
import com.fasterxml.jackson.datatype.hibernate5.Hibernate5Module.Feature;
import com.fasterxml.jackson.datatype.hibernate5.HibernateProxySerializer.ProxyReader;
import com.fasterxml.jackson.datatype.hibernate5.HibernateProxySerializer.ProxySessionReader;

/**
 * Wrapper serializer used to handle aspects of lazy loading that can be used
 * for Hibernate collection datatypes; which includes both
 * <code>Collection</code> and <code>Map</code> types (unlike in JDK).
 */
public class PersistentCollectionSerializer extends ContainerSerializer<Object> implements ContextualSerializer, ResolvableSerializer {
    private static final long              serialVersionUID = 1L;                                                           // since 2.7
    private static final Logger            logger           = LoggerFactory.getLogger(PersistentCollectionSerializer.class);
    /**
     * Type for which underlying serializer was created.
     *
     * @since 2.7
     */
    protected final JavaType               _originalType;
    protected Mapping                      _mapping;
    /**
     * Hibernate-module features set, if any.
     */
    protected final int                    _features;
    /**
     * Serializer that does actual value serialization when value is available
     * (either already or with forced access).
     */
    protected final JsonSerializer<Object> _serializer;
    protected final SessionFactory         _sessionFactory;

    /*
     * /**********************************************************************
     * /* Life cycle
     * /**********************************************************************
     */
    @SuppressWarnings("unchecked")
    public PersistentCollectionSerializer(JavaType containerType, JsonSerializer<?> serializer, Mapping mapping, int features, SessionFactory sessionFactory) {
        super(containerType);
        _mapping = mapping;
        _originalType = containerType;
        _serializer = (JsonSerializer<Object>) serializer;
        _features = features;
        _sessionFactory = sessionFactory;
    }

    /**
     * @since 2.7
     */
    @SuppressWarnings("unchecked")
    protected PersistentCollectionSerializer(PersistentCollectionSerializer base, JsonSerializer<?> serializer) {
        super(base);
        _originalType = base._originalType;
        _serializer = (JsonSerializer<Object>) serializer;
        _features = base._features;
        _sessionFactory = base._sessionFactory;
    }

    @Override
    public PersistentCollectionSerializer unwrappingSerializer(NameTransformer unwrapper) {
        return _withSerializer(_serializer.unwrappingSerializer(unwrapper));
    }

    protected PersistentCollectionSerializer _withSerializer(JsonSerializer<?> ser) {
        if ((ser == _serializer) || (ser == null)) {
            return this;
        }
        return new PersistentCollectionSerializer(this, ser);
    }

    // from `ContainerSerializer`
    @Override
    protected ContainerSerializer<?> _withValueTypeSerializer(TypeSerializer vts) {
        ContainerSerializer<?> ser0 = _containerSerializer();
        if (ser0 != null) {
            return _withSerializer(ser0.withValueTypeSerializer(vts));
        }
        // 03-Jan-2016, tatu: Not sure what to do here; most likely can not make it work without
        //    knowing how to pass various calls... so in a way, should limit to only accepting
        //    ContainerSerializers as delegates.
        return this;
    }

    /*
     * /**********************************************************************
     * /* Contextualization
     * /**********************************************************************
     */
    @Override
    public void resolve(SerializerProvider provider) throws JsonMappingException {
        if (_serializer instanceof ResolvableSerializer) {
            ((ResolvableSerializer) _serializer).resolve(provider);
        }
    }

    /**
     * We need to resolve actual serializer once we know the context;
     * specifically must know type of property being serialized.
     */
    @Override
    public JsonSerializer<?> createContextual(SerializerProvider provider, BeanProperty property) throws JsonMappingException {
        // 18-Oct-2013, tatu: Whether this is for the primary property or secondary is
        //   not quite certain; presume primary one for now.
        JsonSerializer<?> ser = provider.handlePrimaryContextualization(_serializer, property);
        // If we use eager loading, can just return underlying serializer as is
        if (!usesLazyLoading(property)) {
            return ser;
        }
        return _withSerializer(ser);
    }

    /*
     * /**********************************************************************
     * /* JsonSerializer simple accessors, metadata
     * /**********************************************************************
     */
    @Override // since 2.6
    public boolean isEmpty(SerializerProvider provider, Object value) {
        if (value == null) { // is null ever passed?
            return true;
        }
        if (value instanceof PersistentCollection) {
            Object lazy = findLazyValue((PersistentCollection) value);
            return (lazy == null) || _serializer.isEmpty(provider, lazy);
        }
        return _serializer.isEmpty(provider, value);
    }

    @Override
    public boolean isUnwrappingSerializer() {
        return _serializer.isUnwrappingSerializer();
    }

    @Override
    public boolean usesObjectId() {
        return _serializer.usesObjectId();
    }

    @Override
    public void acceptJsonFormatVisitor(JsonFormatVisitorWrapper visitor, JavaType typeHint) throws JsonMappingException {
        _serializer.acceptJsonFormatVisitor(visitor, typeHint);
    }

    /*
     * /**********************************************************************
     * /* ContainerSerializer methods
     * /**********************************************************************
     */
    @Override
    public JavaType getContentType() {
        ContainerSerializer<?> ser = _containerSerializer();
        if (ser != null) {
            return ser.getContentType();
        }
        return _originalType.getContentType();
    }

    @Override
    public JsonSerializer<?> getContentSerializer() {
        ContainerSerializer<?> ser = _containerSerializer();
        if (ser != null) {
            return ser.getContentSerializer();
        }
        // no idea, alas
        return null;
    }

    @Override
    public boolean hasSingleElement(Object value) {
        if (value instanceof Collection<?>) {
            return ((Collection<?>) value).size() == 1;
        }
        if (value instanceof Map<?, ?>) {
            return ((Map<?, ?>) value).size() == 1;
        }
        return false;
    }

    /*
     * /**********************************************************************
     * /* JsonSerializer, actual serialization
     * /**********************************************************************
     */
    @Override
    public void serialize(Object value, JsonGenerator g, SerializerProvider provider) throws IOException {
        if (value instanceof PersistentCollection) {
            value = findLazyValue((PersistentCollection) value);
            if (value == null) {
                provider.defaultSerializeNull(g);
                return;
            }
        }
        if (_serializer == null) { // sanity check...
            throw JsonMappingException.from(g, "PersistentCollection does not have serializer set");
        }
        // 30-Jul-2016, tatu: wrt [datatype-hibernate#93], should NOT have to do anything here;
        //     only affects polymophic cases
        _serializer.serialize(value, g, provider);
    }

    @Override
    public void serializeWithType(Object value, JsonGenerator g, SerializerProvider provider, TypeSerializer typeSer) throws IOException {
        if (value instanceof PersistentCollection) {
            value = findLazyValue((PersistentCollection) value);
            if (value == null) {
                provider.defaultSerializeNull(g);
                return;
            }
        }
        if (_serializer == null) { // sanity check...
            throw JsonMappingException.from(g, "PersistentCollection does not have serializer set");
        }
        // 30-Jul-2016, tatu: wrt [datatype-hibernate#93], conversion IS needed here (or,
        //    if we could figure out, type id)
        // !!! TODO: figure out how to replace type id without having to replace collection
        if (Feature.REPLACE_PERSISTENT_COLLECTIONS.enabledIn(_features)) {
            value = convertToJavaCollection(value); // Strip PersistentCollection
        }
        _serializer.serializeWithType(value, g, provider, typeSer);
    }

    /*
     * /**********************************************************************
     * /* Helper methods
     * /**********************************************************************
     */
    protected ContainerSerializer<?> _containerSerializer() {
        if (_serializer instanceof ContainerSerializer) {
            return (ContainerSerializer<?>) _serializer;
        }
        return null;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    protected Object findLazyValue(PersistentCollection coll) {
        if (coll.wasInitialized()) {
            return coll.getValue();
        }
        if (Feature.FORCE_LAZY_LOADING.enabledIn(_features)) {
            coll.forceInitialization();
            return coll.getValue();
        }
        if (Feature.SERIALIZE_IDENTIFIER_FOR_LAZY_NOT_LOADED_OBJECTS.enabledIn(_features)) {
            //SharedSessionContractImplementor sessionImpl = ((AbstractPersistentCollection) coll).getSession();
            Iterable iterable = getIterable(coll);
            if (iterable != null) {
                return StreamSupport.stream(iterable.spliterator(), false)//
                        .map(o -> {
                            if (o instanceof HibernateProxy) {
                                return proxyToMinimalEntity((HibernateProxy) o);
                            } else {
                                return objectToMinimalEntity(o);
                            }
                        }).collect(Collectors.toList());
            }
        }
        return null;
    }

    private Object objectToMinimalEntity(Object o) {
        String idName = findIdName(o.getClass());
        try {
            final Object obj = o.getClass().newInstance();
            Object idValue = ReflectionUtil.getFieldValue(o, idName);
            logger.debug("idValue:{}", idValue);
            ReflectionUtil.setFieldValue(obj, idName, idValue);
            logger.debug("returning minimal object entity:{}", obj);
            return obj;
        } catch (InstantiationException | IllegalAccessException | SecurityException e) {
            logger.error("Unable to find proxied", e);
            return null;
        }
    }

    private String findIdName(Class<?> cls) {
        logger.debug("findName called for {}", cls);
        if (_sessionFactory != null) {
            return _sessionFactory.getSessionFactory().getIdentifierPropertyName(cls.getName());
        }
        logger.debug("checking fields...");
        for (Field field : cls.getDeclaredFields()) {
            for (Annotation anno : field.getDeclaredAnnotations()) {
                logger.debug("anno:{}", anno);
                if (anno.annotationType().getName().equals("javax.persistence.Id")) {
                    return field.getName();
                }
            }
        }
        logger.debug("checking methods...");
        for (Method method : cls.getDeclaredMethods()) {
            for (Annotation anno : method.getDeclaredAnnotations()) {
                logger.debug("anno:{}", anno.annotationType().getName());
                if (anno.annotationType().getName().equals("javax.persistence.Id")) {
                    return getPropertyName(method);
                }
            }
        }
        Class<?> superClass = cls.getSuperclass();
        if (superClass != null) {
            return findIdName(superClass);
        }
        return null;
    }

    private String getPropertyName(Method method) {
        String name = method.getName();
        if (name.startsWith("get")) {
            return Character.toLowerCase(name.charAt(3)) + name.substring(4);
        }
        return name;
    }

    private Object proxyToMinimalEntity(HibernateProxy proxy) {
        LazyInitializer init = proxy.getHibernateLazyInitializer();
        if (!Feature.FORCE_LAZY_LOADING.enabledIn(_features) && init.isUninitialized()) {
            if (Feature.SERIALIZE_IDENTIFIER_FOR_LAZY_NOT_LOADED_OBJECTS.enabledIn(_features)) {
                String idName;
                if (_mapping != null) {
                    idName = _mapping.getIdentifierPropertyName(init.getEntityName());
                } else {
                    idName = ProxySessionReader.getIdentifierPropertyName(init);
                    if (idName == null) {
                        idName = ProxyReader.getIdentifierPropertyName(init);
                        if (idName == null) {
                            idName = init.getEntityName();
                        }
                    }
                }
                final Object idValue = init.getIdentifier();
                try {
                    logger.debug("entity name:{}", init.getEntityName());
                    final Object obj = Class.forName(init.getEntityName()).newInstance();
                    ReflectionUtil.setFieldValue(obj, idName, idValue);
                    return obj;
                } catch (InstantiationException | IllegalAccessException | ClassNotFoundException | SecurityException e) {
                    logger.error("Unable to find proxied", e);
                    return null;
                }
                //return ImmutableMap.of("type", init.getEntityName(), idName, idValue);
            }
            return null;
        }
        return init.getImplementation();
    }

    @SuppressWarnings("unchecked")
    private Iterable<?> getIterable(PersistentCollection coll) {
        if (coll instanceof PersistentArrayHolder) {
            return () -> ((PersistentArrayHolder) coll).elements();
        }
        if (coll instanceof PersistentBag) {
            return () -> ((PersistentBag) coll).iterator();
        }
        if (coll instanceof PersistentIdentifierBag) {
            return () -> ((PersistentIdentifierBag) coll).iterator();
        }
        if (coll instanceof PersistentList) {
            return () -> ((PersistentList) coll).iterator();
        }
        if (coll instanceof PersistentSet) {
            return () -> ((PersistentSet) coll).iterator();
        }
        if (coll instanceof PersistentMap) {
            return null;//we don't support it for now
        }
        return null;
    }

    /**
     * Method called to see whether given property indicates it uses lazy
     * resolution of reference contained.
     */
    protected boolean usesLazyLoading(BeanProperty property) {
        if (property != null) {
            // As per [Issue#36]
            ElementCollection ec = property.getAnnotation(ElementCollection.class);
            if (ec != null) {
                return (ec.fetch() == FetchType.LAZY);
            }
            OneToMany ann1 = property.getAnnotation(OneToMany.class);
            if (ann1 != null) {
                return (ann1.fetch() == FetchType.LAZY);
            }
            OneToOne ann2 = property.getAnnotation(OneToOne.class);
            if (ann2 != null) {
                return (ann2.fetch() == FetchType.LAZY);
            }
            ManyToOne ann3 = property.getAnnotation(ManyToOne.class);
            if (ann3 != null) {
                return (ann3.fetch() == FetchType.LAZY);
            }
            ManyToMany ann4 = property.getAnnotation(ManyToMany.class);
            if (ann4 != null) {
                return (ann4.fetch() == FetchType.LAZY);
            }
            // As per [Issue#53]
            return !Feature.REQUIRE_EXPLICIT_LAZY_LOADING_MARKER.enabledIn(_features);
        }
        return false;
    }

    // since 2.8.2
    private Object convertToJavaCollection(Object value) {
        if (!(value instanceof PersistentCollection)) {
            return value;
        }
        if (value instanceof Set) {
            return convertToSet((Set<?>) value);
        }
        if (value instanceof List || value instanceof Bag) {
            return convertToList((List<?>) value);
        }
        if (value instanceof Map) {
            return convertToMap((Map<?, ?>) value);
        }
        throw new IllegalArgumentException("Unsupported PersistentCollection subtype: " + value.getClass());
    }

    private Object convertToList(List<?> value) {
        return new ArrayList<>(value);
    }

    private Object convertToMap(Map<?, ?> value) {
        return new HashMap<>(value);
    }

    private Object convertToSet(Set<?> value) {
        return new HashSet<>(value);
    }

    protected static class SessionReader {
        public static boolean isJTA(Session session) {
            try {
                EntityManager em = (EntityManager) session;
                em.getTransaction();
                return false;
            } catch (IllegalStateException e) {
                // EntityManager is required to throw an IllegalStateException if it's JTA-managed
                return true;
            }
        }
    }
}
