/**
 *    Copyright ${license.git.copyrightYears} the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package org.apache.ibatis.builder.xml;

import java.io.InputStream;
import java.io.Reader;
import java.util.Properties;
import javax.sql.DataSource;

import org.apache.ibatis.builder.BaseBuilder;
import org.apache.ibatis.builder.BuilderException;
import org.apache.ibatis.datasource.DataSourceFactory;
import org.apache.ibatis.executor.ErrorContext;
import org.apache.ibatis.executor.loader.ProxyFactory;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.io.VFS;
import org.apache.ibatis.logging.Log;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.parsing.XNode;
import org.apache.ibatis.parsing.XPathParser;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.reflection.DefaultReflectorFactory;
import org.apache.ibatis.reflection.MetaClass;
import org.apache.ibatis.reflection.ReflectorFactory;
import org.apache.ibatis.reflection.factory.ObjectFactory;
import org.apache.ibatis.reflection.wrapper.ObjectWrapperFactory;
import org.apache.ibatis.session.AutoMappingBehavior;
import org.apache.ibatis.session.AutoMappingUnknownColumnBehavior;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.transaction.TransactionFactory;
import org.apache.ibatis.type.JdbcType;

/**
 * @author Clinton Begin
 * @author Kazuki Shimizu
 */
public class XMLConfigBuilder extends BaseBuilder {

  private boolean parsed;
  private final XPathParser parser;
  private String environment;
  private final ReflectorFactory localReflectorFactory = new DefaultReflectorFactory();

  public XMLConfigBuilder(Reader reader) {
    this(reader, null, null);
  }

  public XMLConfigBuilder(Reader reader, String environment) {
    this(reader, environment, null);
  }

  public XMLConfigBuilder(Reader reader, String environment, Properties props) {
    this(new XPathParser(reader, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  public XMLConfigBuilder(InputStream inputStream) {
    this(inputStream, null, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment) {
    this(inputStream, environment, null);
  }

  public XMLConfigBuilder(InputStream inputStream, String environment, Properties props) {
    this(new XPathParser(inputStream, true, props, new XMLMapperEntityResolver()), environment, props);
  }

  private XMLConfigBuilder(XPathParser parser, String environment, Properties props) {
    super(new Configuration());
    ErrorContext.instance().resource("SQL Mapper Configuration");
    this.configuration.setVariables(props);
    this.parsed = false;
    this.environment = environment;
    this.parser = parser;
  }

  /**
   * 解析mybatis_config.xml中的节点
   * @return
   */
  public Configuration parse() {
    // 只会解析一次
    if (parsed) {
      throw new BuilderException("Each XMLConfigBuilder can only be used once.");
    }
    parsed = true;
    // 开始真正解析mybatis_config.xml中的节点
    parseConfiguration(parser.evalNode("/configuration"));
    return configuration;
  }

  /**
   * 开始真正解析mybatis_config.xml中的节点
   * @param root 根节点 configuration
   */
  private void parseConfiguration(XNode root) {
    try {
      //issue #117 read properties first
      // 解析properties元素
      propertiesElement(root.evalNode("properties"));
      Properties settings = settingsAsProperties(root.evalNode("settings"));
      // 加载自定义的VFS实现
      loadCustomVfs(settings);
      // 加载自定义的日志具体实现
      loadCustomLogImpl(settings);
      // 所谓别名，其实就是把你指定的别名对应的class存储在一个map中
      typeAliasesElement(root.evalNode("typeAliases"));
      // 插件解析
      pluginElement(root.evalNode("plugins"));
      // 自定义实例化对象的行为
      objectFactoryElement(root.evalNode("objectFactory"));
      // 配合MateObject插件使用，方便反射操作实体类的对象
      objectWrapperFactoryElement(root.evalNode("objectWrapperFactory"));
      reflectorFactoryElement(root.evalNode("reflectorFactory"));
      settingsElement(settings);
      // read it after objectFactory and objectWrapperFactory issue #631
      // 解析environments元素
      environmentsElement(root.evalNode("environments"));
      // 数据库厂商标识
      databaseIdProviderElement(root.evalNode("databaseIdProvider"));
      // 类型处理器  用来将获取的值以何时的方式转换成Java类型  mybatis默认帮我们实现很多TypeHandler
      typeHandlerElement(root.evalNode("typeHandlers"));
      // 解析映射文件
      mapperElement(root.evalNode("mappers"));
    } catch (Exception e) {
      throw new BuilderException("Error parsing SQL Mapper Configuration. Cause: " + e, e);
    }
  }

  private Properties settingsAsProperties(XNode context) {
    if (context == null) {
      return new Properties();
    }
    Properties props = context.getChildrenAsProperties();
    // Check that all settings are known to the configuration class
    MetaClass metaConfig = MetaClass.forClass(Configuration.class, localReflectorFactory);
    for (Object key : props.keySet()) {
      if (!metaConfig.hasSetter(String.valueOf(key))) {
        throw new BuilderException("The setting " + key + " is not known.  Make sure you spelled it correctly (case sensitive).");
      }
    }
    return props;
  }

  private void loadCustomVfs(Properties props) throws ClassNotFoundException {
    String value = props.getProperty("vfsImpl");
    if (value != null) {
      String[] clazzes = value.split(",");
      for (String clazz : clazzes) {
        if (!clazz.isEmpty()) {
          @SuppressWarnings("unchecked")
          Class<? extends VFS> vfsImpl = (Class<? extends VFS>)Resources.classForName(clazz);
          configuration.setVfsImpl(vfsImpl);
        }
      }
    }
  }

  private void loadCustomLogImpl(Properties props) {
    Class<? extends Log> logImpl = resolveClass(props.getProperty("logImpl"));
    configuration.setLogImpl(logImpl);
  }

  /**
   * 解析别名
   * @param parent typeAliases节点
   *               typeAliases节点中的属性可以有三个，package，url，resource，三者只能存在一个，否则报错
   *    <!--
   *       通过package, 可以直接指定package的名字， mybatis会自动扫描你指定包下面的javabean,
   *       并且默认设置一个别名，默认的名字为： javabean 的首字母小写的非限定类名来作为它的别名。
   *       也可在javabean 加上注解@Alias 来自定义别名， 例如： @Alias(user)
   *       <package name="com.dy.entity"/>
   *    -->
   *   <typeAliases>
   *     <typeAlias type="com.talent.Student" alias="student" />
   *   </typeAliases>
   */
  private void typeAliasesElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 先解析package，先扫描package
        if ("package".equals(child.getName())) {
          String typeAliasPackage = child.getStringAttribute("name");
          // TypeAliasRegistry 负责管理别名，就是通过TypeAliasRegistry 进行别名注册
          configuration.getTypeAliasRegistry().registerAliases(typeAliasPackage);
        } else {
          // 如果子节点是typeAlias节点，那么就获取alias属性和type属性值
          String alias = child.getStringAttribute("alias");
          String type = child.getStringAttribute("type");
          try {
            // 加载类，比如 alias = demoMapper  type = com.talent.DemoMapper
            Class<?> clazz = Resources.classForName(type);
            if (alias == null) {
              // 注册别名
              typeAliasRegistry.registerAlias(clazz);
            } else {
              typeAliasRegistry.registerAlias(alias, clazz);
            }
          } catch (ClassNotFoundException e) {
            throw new BuilderException("Error registering typeAlias for '" + alias + "'. Cause: " + e, e);
          }
        }
      }
    }
  }

  /**
   * 解析插件
   *   <plugins>
   *     <plugin interceptor="">
   *       <property name="" value=""/>
   *     </plugin>
   *   </plugins>
   * @param parent plugins元素
   * @throws Exception
   */
  private void pluginElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        String interceptor = child.getStringAttribute("interceptor");
        Properties properties = child.getChildrenAsProperties();
        Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor).newInstance();
        interceptorInstance.setProperties(properties);
        // 将插件拦截器添加到configuration对象中
        configuration.addInterceptor(interceptorInstance);
      }
    }
  }

  private void objectFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      Properties properties = context.getChildrenAsProperties();
      ObjectFactory factory = (ObjectFactory) resolveClass(type).newInstance();
      factory.setProperties(properties);
      configuration.setObjectFactory(factory);
    }
  }

  private void objectWrapperFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ObjectWrapperFactory factory = (ObjectWrapperFactory) resolveClass(type).newInstance();
      configuration.setObjectWrapperFactory(factory);
    }
  }

  private void reflectorFactoryElement(XNode context) throws Exception {
    if (context != null) {
      String type = context.getStringAttribute("type");
      ReflectorFactory factory = (ReflectorFactory) resolveClass(type).newInstance();
      configuration.setReflectorFactory(factory);
    }
  }

  /**
   * 解析properties元素
   * @param context properties
   * @throws Exception
   */
  private void propertiesElement(XNode context) throws Exception {
    if (context != null) {
      // 将子节点的name以及value属性set进properties对象中.  这里可以注意下顺序，xml配置优先，外部指定的properties配置其次
      Properties defaults = context.getChildrenAsProperties();
      // <properties resource="" url="" /> 二者只能配置一个
      String resource = context.getStringAttribute("resource");
      String url = context.getStringAttribute("url");
      if (resource != null && url != null) {
        throw new BuilderException("The properties element cannot specify both a URL and a resource based property file reference.  Please specify one or the other.");
      }
      if (resource != null) {
        defaults.putAll(Resources.getResourceAsProperties(resource));
      } else if (url != null) {
        defaults.putAll(Resources.getUrlAsProperties(url));
      }
      // 将configuration对象中已配置的properties属性与刚刚解析的融合，configuration这个对象会装载所解析mybatis配置文件的所有节点元素，以后也会频繁用到这个对象
      // 既然configuration对象有一系列get/set方法，那是否就标志着我们可以使用java代码直接配置？
      // 答案是肯定的，不过使用配置文件进行配置，优势不言而喻
      Properties vars = configuration.getVariables();
      if (vars != null) {
        defaults.putAll(vars);
      }
      // 设置properties属性到xml解析器中
      parser.setVariables(defaults);
      // 设置properties属性到configuration对象中
      configuration.setVariables(defaults);
    }
  }

  /**
   * 如果不知道settings中有哪些配置，可以看这个。解析
   * @param props
   */
  private void settingsElement(Properties props) {
    // 指定 MyBatis 应如何自动映射列到字段或属性。 NONE 表示取消自动映射；PARTIAL 只会自动映射没有定义嵌套结果集映射的结果集。 FULL 会自动映射任意复杂的结果集（无论是否嵌套）。
    configuration.setAutoMappingBehavior(AutoMappingBehavior.valueOf(props.getProperty("autoMappingBehavior", "PARTIAL")));
    configuration.setAutoMappingUnknownColumnBehavior(AutoMappingUnknownColumnBehavior.valueOf(props.getProperty("autoMappingUnknownColumnBehavior", "NONE")));
    // 一二级缓存默认是开启的，但是二级缓存对于每个mapper是关闭的，需要手动打开
    configuration.setCacheEnabled(booleanValueOf(props.getProperty("cacheEnabled"), true));
    configuration.setProxyFactory((ProxyFactory) createInstance(props.getProperty("proxyFactory")));
    // 延迟加载的全局开关。当开启时，所有关联对象都会延迟加载。 特定关联关系中可通过设置fetchType属性来覆盖该项的开关状态 默认为false
    configuration.setLazyLoadingEnabled(booleanValueOf(props.getProperty("lazyLoadingEnabled"), false));
    // 当启用时，对任意延迟属性的调用会使带有延迟加载属性的对象完整加载，反之，每种属性将会按需加载 默认为false
    configuration.setAggressiveLazyLoading(booleanValueOf(props.getProperty("aggressiveLazyLoading"), false));
    // 是否允许单一语句返回多结果集(需要兼容驱动) 默认为true
    configuration.setMultipleResultSetsEnabled(booleanValueOf(props.getProperty("multipleResultSetsEnabled"), true));
    // 使用列标签代替列名 默认为true
    configuration.setUseColumnLabel(booleanValueOf(props.getProperty("useColumnLabel"), true));
    // 允许jdbc支持自动生成主键，需要兼容驱动 如果设置为true，则这个主键强制使用自动生成主键 默认为false
    configuration.setUseGeneratedKeys(booleanValueOf(props.getProperty("useGeneratedKeys"), false));
    // 配置默认的执行器  默认为SIMPLE
    configuration.setDefaultExecutorType(ExecutorType.valueOf(props.getProperty("defaultExecutorType", "SIMPLE")));
    // 设置超时时间，它决定驱动等待数据库响应的秒数。 默认为null
    configuration.setDefaultStatementTimeout(integerValueOf(props.getProperty("defaultStatementTimeout"), null));
    configuration.setDefaultFetchSize(integerValueOf(props.getProperty("defaultFetchSize"), null));
    configuration.setDefaultResultSetType(resolveResultSetType(props.getProperty("defaultResultSetType")));
    // 是否开启驼峰命名规则，默认为false
    configuration.setMapUnderscoreToCamelCase(booleanValueOf(props.getProperty("mapUnderscoreToCamelCase"), false));
    // 允许在嵌套语句中使用分页(RowBounds)默认为false
    configuration.setSafeRowBoundsEnabled(booleanValueOf(props.getProperty("safeRowBoundsEnabled"), false));
    // mybatis一级缓存默认的作用域是SESSION级别的.即在同一个sqlSession中，执行相同的sql，只会执行一遍。可以将作用域改成STATEMENT，这样每次执行完后，都会将一级缓存清空
    configuration.setLocalCacheScope(LocalCacheScope.valueOf(props.getProperty("localCacheScope", "SESSION")));
    // 当没有为参数提供特定的jdbc类型时，为空值指定jdbc类型 默认为other
    configuration.setJdbcTypeForNull(JdbcType.valueOf(props.getProperty("jdbcTypeForNull", "OTHER")));
    // 指定哪个对象的方法触发一次延迟加载
    configuration.setLazyLoadTriggerMethods(stringSetValueOf(props.getProperty("lazyLoadTriggerMethods"), "equals,clone,hashCode,toString"));
    //
    configuration.setSafeResultHandlerEnabled(booleanValueOf(props.getProperty("safeResultHandlerEnabled"), true));
    configuration.setDefaultScriptingLanguage(resolveClass(props.getProperty("defaultScriptingLanguage")));
    configuration.setDefaultEnumTypeHandler(resolveClass(props.getProperty("defaultEnumTypeHandler")));
    // 指定当结果集中值为 null 的时候是否调用映射对象的 setter（map 对象时为 put）方法，这对于有 Map.keySet() 依赖或 null 值初始化的时候是有用的。注意基本类型（int、boolean等）是不能设置成 null 的 默认为false
    configuration.setCallSettersOnNulls(booleanValueOf(props.getProperty("callSettersOnNulls"), false));
    configuration.setUseActualParamName(booleanValueOf(props.getProperty("useActualParamName"), true));
    configuration.setReturnInstanceForEmptyRow(booleanValueOf(props.getProperty("returnInstanceForEmptyRow"), false));
    // 指定mybatis增加到日志名称的前缀
    configuration.setLogPrefix(props.getProperty("logPrefix"));
    configuration.setConfigurationFactory(resolveClass(props.getProperty("configurationFactory")));
  }

  /**
   * 解析jdbc节点
   * @param context environments节点
   * @throws Exception
   */
  private void environmentsElement(XNode context) throws Exception {
    if (context != null) {
      if (environment == null) {
        // 获取environments节点中default的值，即默认的值  mybatis_config.xml配置文件 default = development
        environment = context.getStringAttribute("default");
      }
      // 循环解析environments字节点
      for (XNode child : context.getChildren()) {
        // 获取environment节点中的id属性 mybatis_config.xml配置文件id = development
        String id = child.getStringAttribute("id");
        // 从mybatis_config.xml配置文件中可以发现environment节点的id值和environments节点的default值是一样的，因此会进if块。同理environment可以配置多个，使用default进行切换对应的环境
        if (isSpecifiedEnvironment(id)) {
          // 解析事务管理  mybatis有两种：JDBC和MANAGED，配置为JDBC则直接使用JDBC事务，配置MANAGED则是将事务托管给容器
          TransactionFactory txFactory = transactionManagerElement(child.evalNode("transactionManager"));
          // 解析数据源
          DataSourceFactory dsFactory = dataSourceElement(child.evalNode("dataSource"));
          DataSource dataSource = dsFactory.getDataSource();
          Environment.Builder environmentBuilder = new Environment.Builder(id)
              .transactionFactory(txFactory)
              .dataSource(dataSource);
          // 将dataSource设置进configuration对象中
          configuration.setEnvironment(environmentBuilder.build());
        }
      }
    }
  }

  /**
   * 解析数据库厂商标识
   * @param context databaseIdProvider节点
   * @throws Exception
   */
  private void databaseIdProviderElement(XNode context) throws Exception {
    DatabaseIdProvider databaseIdProvider = null;
    if (context != null) {
      // databaseIdProvider节点的type属性 type = DB_VENDOR
      String type = context.getStringAttribute("type");
      // awful patch to keep backward compatibility
      if ("VENDOR".equals(type)) {
        type = "DB_VENDOR";
      }
      Properties properties = context.getChildrenAsProperties();
      databaseIdProvider = (DatabaseIdProvider) resolveClass(type).newInstance();
      databaseIdProvider.setProperties(properties);
    }
    Environment environment = configuration.getEnvironment();
    if (environment != null && databaseIdProvider != null) {
      String databaseId = databaseIdProvider.getDatabaseId(environment.getDataSource());
      // 需要在select或者insert或者update或者delete节点中添加databaseId来指定
      configuration.setDatabaseId(databaseId);
    }
  }

  /**
   * 解析事务管理
   * @param context transactionManager节点
   * @return
   * @throws Exception
   */
  private TransactionFactory transactionManagerElement(XNode context) throws Exception {
    if (context != null) {
      // 获取 transactionManager节点中的type值  type = JDBC
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      // 事务工厂创建
      TransactionFactory factory = (TransactionFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a TransactionFactory.");
  }

  /**
   * 解析数据源
   * @param context  dataSource节点
   * @return
   * @throws Exception
   */
  private DataSourceFactory dataSourceElement(XNode context) throws Exception {
    if (context != null) {
      // 解析dataSource节点的type属性  type = POOLED
      String type = context.getStringAttribute("type");
      Properties props = context.getChildrenAsProperties();
      // 创建数据源工厂
      DataSourceFactory factory = (DataSourceFactory) resolveClass(type).newInstance();
      factory.setProperties(props);
      return factory;
    }
    throw new BuilderException("Environment declaration requires a DataSourceFactory.");
  }

  /**
   * 配置类型处理器， 需要实现TypeHandler接口实现自定义的TypeHandler类型处理器
   * <typeHandlers>
   *     <!--
   *       当配置package的时候，mybatis会去配置的package扫描typeHandler
   *       <package name="com.talent.Student" />
   *     -->
   *     <!-- handler属性直接配置我们要指定的TypeHandler -->
   *     <typeHandler handler="" />
   *     <!-- javaType 配置的java类型，例如String，如果配置javaType，那么指定的typeHandler就只作用于指定类型 -->
   *     <typeHandler handler="" javaType="" />
   *     <!-- jdbcType 配置数据库基本年数据类型，例如varchar，如果配上jdbcType，那么指定的typeHandler就只作用于指定的类型 -->
   *     <typeHandler handler="" jdbcType="" />
   *     <!--  也可两者都配置 -->
   *     <typeHandler handler="" jdbcType="" javaType="" />
   *   </typeHandlers>
   * @param parent typeHandlers元素
   */
  private void typeHandlerElement(XNode parent) {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 子节点为package时，获取其name属性的值，然后自动扫描package下的自定义的typeHandler
        if ("package".equals(child.getName())) {
          String typeHandlerPackage = child.getStringAttribute("name");
          typeHandlerRegistry.register(typeHandlerPackage);
        } else {
          // 子节点typeHandler时，可以指定javaType属性，也可以指定jdbcType，也可两者都指定
          String javaTypeName = child.getStringAttribute("javaType");
          String jdbcTypeName = child.getStringAttribute("jdbcType");
          // handler就是我们配置的typeHandler
          String handlerTypeName = child.getStringAttribute("handler");
          Class<?> javaTypeClass = resolveClass(javaTypeName);
          // JdcbType是一个枚举类型，resolveJdbcType方法是在获取枚举类型的值
          JdbcType jdbcType = resolveJdbcType(jdbcTypeName);
          Class<?> typeHandlerClass = resolveClass(handlerTypeName);
          // 注册typeHandler，typeHandler通过TypeHandlerRegistry这个类管理
          if (javaTypeClass != null) {
            if (jdbcType == null) {
              typeHandlerRegistry.register(javaTypeClass, typeHandlerClass);
            } else {
              typeHandlerRegistry.register(javaTypeClass, jdbcType, typeHandlerClass);
            }
          } else {
            typeHandlerRegistry.register(typeHandlerClass);
          }
        }
      }
    }
  }

  /**
   * 解析映射文件
   * @param parent mappers节点
   * @throws Exception
   */
  private void mapperElement(XNode parent) throws Exception {
    if (parent != null) {
      for (XNode child : parent.getChildren()) {
        // 先判断是不是package  到底是解析注解还是解析xml
        if ("package".equals(child.getName())) {
          String mapperPackage = child.getStringAttribute("name");
          configuration.addMappers(mapperPackage);
        } else {
          // 请注意：三者只能配置其中的一个
          String resource = child.getStringAttribute("resource");
          String url = child.getStringAttribute("url");
          String mapperClass = child.getStringAttribute("class");
          if (resource != null && url == null && mapperClass == null) {
            ErrorContext.instance().resource(resource);
            InputStream inputStream = Resources.getResourceAsStream(resource);
            // 解析<mapper resource=""/>指定的映射文件xml
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, resource, configuration.getSqlFragments());
            // 解析
            mapperParser.parse();
          } else if (resource == null && url != null && mapperClass == null) {
            ErrorContext.instance().resource(url);
            InputStream inputStream = Resources.getUrlAsStream(url);
            XMLMapperBuilder mapperParser = new XMLMapperBuilder(inputStream, configuration, url, configuration.getSqlFragments());
            mapperParser.parse();
          } else if (resource == null && url == null && mapperClass != null) {
            Class<?> mapperInterface = Resources.classForName(mapperClass);
            configuration.addMapper(mapperInterface);
          } else {
            throw new BuilderException("A mapper element may only specify a url, resource or class, but not more than one.");
          }
        }
      }
    }
  }

  private boolean isSpecifiedEnvironment(String id) {
    if (environment == null) {
      throw new BuilderException("No environment specified.");
    } else if (id == null) {
      throw new BuilderException("Environment requires an id attribute.");
    } else if (environment.equals(id)) {
      return true;
    }
    return false;
  }

}
