package com.talent;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * @program: TestArgs
 * @author: guobing
 * @description:
 * @create: 2019-09-23 09:35
 */
public class TestArgs {

  public void test(String name, String address) {

  }

  public static void main(String[] args) throws NoSuchMethodException, IllegalAccessException {
    // 每个方法都有一个MethodType对象，因此，首先获取test方法的MethodType对象
    MethodType testMethodType = MethodType.methodType(void.class, String.class, String.class);

    // 找到方法句柄
    MethodHandle testMethodHandle = MethodHandles.lookup().findVirtual(TestArgs.class, "test", testMethodType);

    Method testMethod = TestArgs.class.getMethod("test", String.class, String.class);
    for (Parameter parameter : testMethod.getParameters()) {
      System.out.println(parameter.getName());
    }
  }
}
