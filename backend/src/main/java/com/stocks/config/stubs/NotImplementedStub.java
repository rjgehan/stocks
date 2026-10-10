package com.stocks.config.stubs;

import com.stocks.common.error.ApiException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Objects;

/**
 * Builds a "not implemented" stub for a contract interface (§27.1): a JDK proxy whose every abstract method throws
 * {@code ApiException(NOT_IMPLEMENTED)} (HTTP 501, {@link ApiException#notImplemented}). Default interface methods run
 * normally; {@code toString}, {@code equals} and {@code hashCode} behave like an ordinary object.
 */
public final class NotImplementedStub implements InvocationHandler {

  private final Class<?> contract;
  private final String owner;

  private NotImplementedStub(Class<?> contract, String owner) {
    this.contract = contract;
    this.owner = owner;
  }

  /** A stub of {@code contract} that also implements {@link ContractStub} with the given owner. */
  public static <T> T of(Class<T> contract, String owner) {
    Objects.requireNonNull(contract, "contract");
    Objects.requireNonNull(owner, "owner");
    if (!contract.isInterface()) {
      throw new IllegalArgumentException(contract.getName() + " is not an interface");
    }
    Object proxy = Proxy.newProxyInstance(contract.getClassLoader(), new Class<?>[] {contract, ContractStub.class},
        new NotImplementedStub(contract, owner));
    return contract.cast(proxy);
  }

  @Override
  public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    if (method.getDeclaringClass() == Object.class) {
      return switch (method.getName()) {
        case "toString" -> "NotImplementedStub[" + contract.getSimpleName() + ", " + owner + "]";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == args[0];
        default -> throw new UnsupportedOperationException(method.getName());
      };
    }
    if (method.getDeclaringClass() == ContractStub.class) {
      return owner;
    }
    if (method.isDefault()) {
      return InvocationHandler.invokeDefault(proxy, method, args);
    }
    throw ApiException.notImplemented(contract.getSimpleName() + "." + method.getName() + " (" + owner + ")");
  }
}
