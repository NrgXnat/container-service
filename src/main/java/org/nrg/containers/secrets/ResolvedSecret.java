package org.nrg.containers.secrets;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import org.nrg.containers.exceptions.ContainerServiceSecretException;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;

// Treat this as the parent type when we serialize / deserialize.
// That way we do not serialize any secret values
@JsonSerialize(as = Secret.class)
@JsonDeserialize(using = ResolvedSecret.JsonDeserializer.class)
public class ResolvedSecret extends Secret {
    public ResolvedSecret(final Secret secret) {
        super(secret.source(), secret.destination());
    }

    public ResolvedSecret(final Secret secret,
                          @SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "unused"}) final Optional<String> secretValue) {
        this(secret);
    }

    public static ResolvedSecret fromUnresolved(final Secret secret) {
        return new ResolvedSecret(secret);
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        return Objects.hash(super.hashCode());
    }

    @Override
    public String toString() {
        return "Resolved" + super.toString();
    }

    public static class JsonDeserializer extends com.fasterxml.jackson.databind.JsonDeserializer<ResolvedSecret> {
        @Override
        public ResolvedSecret deserialize(final JsonParser jsonParser, final DeserializationContext deserializationContext) throws IOException {
            return fromUnresolved(jsonParser.readValueAs(Secret.class));
        }
    }

    public static class WithValue extends ResolvedSecret {
        final String value;

        public WithValue(final Secret secret,
                         @SuppressWarnings("OptionalUsedAsFieldOrParameterType") final Optional<String> value)
                throws ContainerServiceSecretException {
            this(secret, value.orElseThrow(() -> new ContainerServiceSecretException("Did not obtain value for secret " + secret)));
        }

        public WithValue(final Secret secret, @Nonnull final String value) {
            super(secret);
            this.value = value;
        }

        public String value() {
            return value;
        }

        @Override
        public boolean equals(final Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            if (!super.equals(o)) return false;
            final WithValue withValue = (WithValue) o;
            return Objects.equals(value, withValue.value);
        }

        @Override
        public int hashCode() {
            return Objects.hash(super.hashCode(), value);
        }

        @Override
        public String toString() {
            // Explicitly calling ResolvedSecret toString as a message to future developers:
            //  do not write the secret value to a string
            return super.toString();
        }
    }
}
