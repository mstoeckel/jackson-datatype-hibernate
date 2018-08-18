package com.fasterxml.jackson.datatype.hibernate5;

import java.lang.reflect.Field;

public class ReflectionUtil {
    public static Object getFieldValue(Object obj, String fieldName) throws IllegalArgumentException, IllegalAccessException {
        Field field = findField(obj.getClass(), fieldName);
        Object result = null;
        if (field != null) {
            boolean accessible = field.isAccessible();
            try {
                field.setAccessible(true);
                result = field.get(obj);
            } finally {
                field.setAccessible(accessible);
            }
        }
        return result;
    }

    public static void setFieldValue(Object obj, String fieldName, Object value) throws IllegalArgumentException, IllegalAccessException {
        Field field = findField(obj.getClass(), fieldName);
        if (field != null) {
            boolean accessible = field.isAccessible();
            field.setAccessible(true);
            field.set(obj, value);
            field.setAccessible(accessible);
        }
    }

    private static Field findField(Class<?> cls, String fieldName) {
        if (cls == null) {
            return null;
        }
        for (Field field : cls.getDeclaredFields()) {
            if (field.getName().equals(fieldName)) {
                return field;
            }
        }
        return findField(cls.getSuperclass(), fieldName);
    }
}
