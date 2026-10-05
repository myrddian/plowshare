package io.aeyer.plowshare.sdk;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Prevents public Java SDK operations from regressing to raw transport or property-bag contracts.
 */
class PublicContractsTest {
  @Test
  void public_operations_and_their_dto_components_do_not_expose_raw_json_or_object_payloads() {
    Set<Type> visited = new HashSet<>();
    for (Class<?> facade :
        List.of(
            Plowshare.class,
            ServerClient.class,
            WsServerClient.class,
            AdministrationClient.class,
            AuthClient.class,
            AgentClient.class,
            IncomingClient.class,
            OutgoingClient.class,
            OrchestrationClient.class,
            UsageClient.class,
            ExternalPayloadCodec.class,
            AgentCardCodec.class)) {
      for (Method method : facade.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic()) continue;
        checked(method.getGenericReturnType(), method.toString(), visited);
        for (Type parameter : method.getGenericParameterTypes())
          checked(parameter, method.toString(), visited);
      }
      for (Constructor<?> constructor : facade.getConstructors())
        for (Type parameter : constructor.getGenericParameterTypes())
          checked(parameter, constructor.toString(), visited);
    }
  }

  private static void checked(Type type, String context, Set<Type> visited) {
    if (!visited.add(type)) return;
    if (type instanceof ParameterizedType generic) {
      for (Type parameter : generic.getActualTypeArguments()) checked(parameter, context, visited);
      return;
    }
    if (type instanceof WildcardType wildcard) {
      for (Type bound : wildcard.getUpperBounds()) checked(bound, context, visited);
      for (Type bound : wildcard.getLowerBounds()) checked(bound, context, visited);
      return;
    }
    if (type instanceof GenericArrayType array) {
      checked(array.getGenericComponentType(), context, visited);
      return;
    }
    assertInstanceOf(Class.class, type, "Unbounded public contract: " + context);
    Class<?> value = (Class<?>) type;
    assertNotEquals(Object.class, value, "Generic object payload: " + context);
    assertFalse(
        value.getName().startsWith("com.fasterxml.jackson."), "Raw JSON contract: " + context);
    assertFalse(value.getName().equals("org.json.JSONObject"), "Raw JSON contract: " + context);
    assertFalse(
        Map.class.isAssignableFrom(value) || Collection.class.isAssignableFrom(value),
        "Raw collection contract: " + context);
    if (value.isArray()) checked(value.getComponentType(), context, visited);
    if (value.isRecord())
      for (RecordComponent component : value.getRecordComponents())
        checked(component.getGenericType(), value.getName() + "." + component.getName(), visited);
    if (value.isSealed())
      for (Class<?> variant : value.getPermittedSubclasses()) checked(variant, context, visited);
  }
}
