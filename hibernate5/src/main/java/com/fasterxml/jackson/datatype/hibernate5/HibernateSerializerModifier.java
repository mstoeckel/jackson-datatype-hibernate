package com.fasterxml.jackson.datatype.hibernate5;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializationConfig;
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier;
import com.fasterxml.jackson.databind.type.CollectionType;
import com.fasterxml.jackson.databind.type.MapType;
import org.hibernate.SessionFactory;
import org.hibernate.engine.spi.Mapping;

public class HibernateSerializerModifier
    extends BeanSerializerModifier
{
    protected final Mapping _mapping;
    protected final int _features;

    protected final SessionFactory _sessionFactory;

    public HibernateSerializerModifier(Mapping mapping, int features, SessionFactory sessionFactory) {
        _mapping = mapping;
        _features = features;
        _sessionFactory = sessionFactory;
    }
    
    /*
    @Override
    public JsonSerializer<?> modifySerializer(SerializationConfig config,
            BeanDescription beanDesc, JsonSerializer<?> serializer) {
        return serializer;
    }
    */

    @Override
    public JsonSerializer<?> modifyCollectionSerializer(SerializationConfig config,
            CollectionType valueType, BeanDescription beanDesc, JsonSerializer<?> serializer) {
        return new PersistentCollectionSerializer(valueType, serializer, _mapping, _features, _sessionFactory);
    }

    @Override
    public JsonSerializer<?> modifyMapSerializer(SerializationConfig config,
            MapType valueType, BeanDescription beanDesc, JsonSerializer<?> serializer) {
        return new PersistentCollectionSerializer(valueType, serializer, _mapping, _features, _sessionFactory);
    }
}
