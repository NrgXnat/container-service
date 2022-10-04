package org.nrg.containers.services.impl;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.nrg.containers.exceptions.ContainerServiceSecretException;
import org.nrg.containers.secrets.ResolvedSecret;
import org.nrg.containers.secrets.ResolverFor;
import org.nrg.containers.secrets.Secret;
import org.nrg.containers.secrets.SecretDestination;
import org.nrg.containers.secrets.SecretSource;
import org.nrg.containers.secrets.SecretValueObtainer;
import org.nrg.containers.services.ContainerSecretService;
import org.reflections.Reflections;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ContainerSecretServiceImpl implements ContainerSecretService {
    private final Map<Class<? extends SecretSource>, SecretValueObtainer<?>> valueObtainers;
    private final List<Class<? extends ResolvedSecret>> annotatedResolvedSecretTypes;
    private final Map<Pair<Class<? extends SecretSource>, Class<? extends SecretDestination>>,
            Optional<Class<? extends ResolvedSecret>>> resolvedSecretTypeMap;

    @SuppressWarnings("unchecked")
    public ContainerSecretServiceImpl(final List<SecretValueObtainer<?>> valueObtainers) {
        this.valueObtainers = valueObtainers.stream()
                .collect(Collectors.collectingAndThen(
                        Collectors.toMap(SecretValueObtainer::handledType, Function.identity()),
                        Collections::unmodifiableMap)
                );

        annotatedResolvedSecretTypes = new Reflections("org.nrg.containers.secrets")
                .getTypesAnnotatedWith(ResolverFor.class, true)
                .stream()
                .filter(ResolvedSecret.class::isAssignableFrom)
                .map(clazz -> (Class<? extends ResolvedSecret>) clazz)
                .collect(Collectors.collectingAndThen(Collectors.toList(), Collections::unmodifiableList));
        resolvedSecretTypeMap = new ConcurrentHashMap<>();
    }

    private <T extends SecretSource> Optional<String> obtainValue(final T source) throws ContainerServiceSecretException {
        if (!(source instanceof SecretSource.ValueObtainingSecretSource)) {
            return Optional.empty();
        }

        // Find the obtainer for this kind of source and use it to obtain the value
        return Optional.ofNullable(valueObtainers.get(source.getClass()))
                .orElseThrow(() -> new ContainerServiceSecretException("No value obtainer for source type " + source.getClass().getSimpleName()))
                .obtainValue((SecretSource.ValueObtainingSecretSource) source);
    }

    @Override
    public ResolvedSecret resolve(final Secret secret) throws ContainerServiceSecretException {
        log.debug("Resolving secret {}", secret);

        final Class<? extends ResolvedSecret> resolvedSecretClass = findResolvedSecretClass(secret);
        final Optional<String> secretValue = obtainValue(secret.source());

        try {
            final Constructor<? extends ResolvedSecret> c = resolvedSecretClass.getConstructor(Secret.class, Optional.class);
            return c.newInstance(secret, secretValue);
        } catch (NoSuchMethodException | InvocationTargetException | InstantiationException | IllegalAccessException e) {
            throw new ContainerServiceSecretException("Could not create " + resolvedSecretClass.getSimpleName(), e);
        }
    }

    private Class<? extends ResolvedSecret> findResolvedSecretClass(final Secret secret)
            throws ContainerServiceSecretException {
        final Pair<Class<? extends SecretSource>, Class<? extends SecretDestination>> key =
                Pair.of(secret.source().getClass(), secret.destination().getClass());
        return resolvedSecretTypeMap.computeIfAbsent(key, this::findResolvedSecretClass)
                .orElseThrow(() -> new ContainerServiceSecretException("Could not find resolver for secret " + secret));
    }

    private Optional<Class<? extends ResolvedSecret>> findResolvedSecretClass(
            final Pair<Class<? extends SecretSource>, Class<? extends SecretDestination>> key
    ) {
        final Class<? extends SecretSource> sourceClass = key.getLeft();
        final Class<? extends SecretDestination> destinationClass = key.getRight();
        return annotatedResolvedSecretTypes.stream()
                .filter(clazz -> {
                    final ResolverFor annotation = clazz.getAnnotation(ResolverFor.class);
                    return annotation.destination().isAssignableFrom(destinationClass) &&
                            (annotation.source() == SecretSource.AnySource.class ||
                                    sourceClass != null && annotation.source().isAssignableFrom(sourceClass));
                })
                .findFirst();
    }
}
