package hauntedjvm.persistence;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.fasterxml.jackson.databind.module.SimpleModule;
import hauntedjvm.core.entity.EntityId;
import hauntedjvm.core.entity.Facet;
import hauntedjvm.core.event.SimEvent;
import java.io.IOException;
import java.time.Instant;

/**
 * Jackson configuration for the simulation's types.
 *
 * <p>The core module has no serialisation annotations at all. Polymorphism is attached from
 * here with mix-ins, and subtypes are discovered by walking the sealed hierarchies with
 * {@link Class#getPermittedSubclasses()}: adding an event record to the core automatically makes
 * it serialisable, with no registry to forget to update.
 */
public final class JsonCodec {

    /** Type discriminator property; prefixed so it can never collide with a record component. */
    static final String TYPE_PROPERTY = "@type";

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = TYPE_PROPERTY)
    private abstract static class Polymorphic {
    }

    private JsonCodec() {
    }

    public static ObjectMapper mapper() {
        ObjectMapper mapper = new ObjectMapper();
        // Records are read and written through their components only. Convenience methods such as
        // Entity.mind() or Decaying.at(long) must never leak into the format.
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE);
        mapper.setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);
        mapper.setVisibility(PropertyAccessor.CREATOR, JsonAutoDetect.Visibility.ANY);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);
        mapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, true);
        mapper.addMixIn(SimEvent.class, Polymorphic.class);
        mapper.addMixIn(Facet.class, Polymorphic.class);
        registerSealed(mapper, SimEvent.class);
        registerSealed(mapper, Facet.class);
        mapper.registerModule(scalars());
        return mapper;
    }

    private static void registerSealed(ObjectMapper mapper, Class<?> root) {
        for (Class<?> sub : root.getPermittedSubclasses()) {
            if (sub.isSealed()) {
                registerSealed(mapper, sub);
            } else {
                mapper.registerSubtypes(new NamedType(sub, sub.getSimpleName()));
            }
        }
    }

    /** Entity ids are plain numbers on disk; instants are ISO-8601 strings. */
    private static SimpleModule scalars() {
        SimpleModule module = new SimpleModule("hauntedjvm-scalars");
        module.addSerializer(EntityId.class, new JsonSerializer<>() {
            @Override
            public void serialize(EntityId value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeNumber(value.value());
            }
        });
        module.addDeserializer(EntityId.class, new JsonDeserializer<>() {
            @Override
            public EntityId deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return EntityId.of(p.getIntValue());
            }
        });
        module.addSerializer(Instant.class, new JsonSerializer<>() {
            @Override
            public void serialize(Instant value, JsonGenerator gen, SerializerProvider provider) throws IOException {
                gen.writeString(value.toString());
            }
        });
        module.addDeserializer(Instant.class, new JsonDeserializer<>() {
            @Override
            public Instant deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                return Instant.parse(p.getValueAsString());
            }
        });
        return module;
    }
}
