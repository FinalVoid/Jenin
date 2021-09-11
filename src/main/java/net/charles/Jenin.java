package net.charles;

import com.google.gson.ExclusionStrategy;
import net.charles.messaging.ChannelManager;
import net.charles.parser.JeninParser;
import net.charles.parser.KeyManager;
import net.charles.parser.SearchFilter;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.ScanParams;
import redis.clients.jedis.exceptions.JedisDataException;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

public class Jenin extends JeninParser {
    private final JedisPool pool;

    public Jenin(final JedisPool jedisPool) {
        this.pool = jedisPool;
    }

    @Override
    public JeninParser updateExclusionStrategy(ExclusionStrategy serializationExclusionStrategy, ExclusionStrategy deserializationExclusionStrategy) {
        rebuildGson(getGson().newBuilder().serializeNulls().addSerializationExclusionStrategy(serializationExclusionStrategy).addDeserializationExclusionStrategy(deserializationExclusionStrategy));
        return this;
    }

    @Override
    public JeninParser updateExclusionStrategy(ExclusionStrategy... exclusionStrategy) {
        rebuildGson(getGson().newBuilder().serializeNulls().setExclusionStrategies(exclusionStrategy));
        return this;
    }

    @Override
    public JeninParser registerTypeAdapter(Type type, Object adapter) {
        rebuildGson(getGson().newBuilder().registerTypeAdapter(type, adapter));
        return this;
    }

    @Override
    public void push(String key, String json) {
        withJedis(jedis -> jedis.set(key, json));
    }

    @Override
    public <T> void push(T t) {
        withJedis(jedis -> {
            try {
                String key = KeyManager.findKey(t);
                jedis.set(key, getGson().toJson(t, t.getClass()));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public <T> void pushToHashSet(T t) {
        withJedis(jedis -> {
            try {
                String key = KeyManager.findKey(t);
                jedis.hset(key, convertToHashSet(t));
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public void pushToHashSet(String key, Map<String, String> obj) {
        withJedis(jedis -> jedis.hset(key, obj));
    }

    @Override
    public <T> T search(String key, Class<T> clazz) {
        return getWithJedis(jedis -> getGson().fromJson(jedis.get(key), clazz));
    }

    @Override
    public String search(String key, String fieldName, Class<?> clazz) {
        return getWithJedis(jedis -> {
            Object obj = getGson().fromJson(jedis.get(key), clazz);
            return getGson().toJsonTree(obj).getAsJsonObject().get(fieldName).getAsString();
        });
    }

    @Override
    public <T> T hashSearch(String key, Class<T> clazz) {
        return getWithJedis(jedis -> convertToObject(key, jedis.hgetAll(key), clazz));
    }

    @Override
    public String hashSearch(String key, String fieldName) {
        return getWithJedis(jedis -> jedis.hget(key, fieldName));
    }

    @Override
    public <V, C> List<C> hashSearch(SearchFilter<V>[] searchFilters, Class<C> clazz) {
        return getWithJedis(jedis -> {
            final List<C> objects = new ArrayList<>();
            for (String v : jedis.scan("0", new ScanParams().match("*")).getResult()) {
                try {
                    if (jedis.hexists(v, searchFilters[0].getFieldName())) {
                        C c = convertToObject(v, jedis.hgetAll(v), clazz);
                        if (applyFilter(c, searchFilters)) objects.add(c);
                    }
                } catch (JedisDataException ignored) {
                } catch (NoSuchFieldException | IllegalAccessException e) {
                    e.printStackTrace();
                }
            }
            return objects;
        });
    }

    @Override
    public ChannelManager getChannelManagerInstance() {
        return getChannelManager(pool.getResource());
    }

    private <R> R getWithJedis(Function<Jedis, R> function) {
        try (Jedis jedis = pool.getResource()) {
            return function.apply(jedis);
        }
    }

    private void withJedis(Consumer<Jedis> consumer) {
        try (Jedis jedis = pool.getResource()) {
            consumer.accept(jedis);
        }
    }
}