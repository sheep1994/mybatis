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
package com.talent;

import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * @program: Test
 * @author: guobing
 * @description:
 * @create: 2019-09-18 10:10
 */
public class Test {

  public static void main(String[] args) {

    String resource = "mybatis_config.xml";

    System.out.println(Object.class.equals(Test.class));

    InputStream stream = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource);
    // 创建sqlSessionFactory
    SqlSessionFactory sqlSessionFactory = new SqlSessionFactoryBuilder().build(stream);
    // 打开一个sqlSession， mybatis默认不是自动提交
    SqlSession sqlSession = sqlSessionFactory.openSession();
    // 拿到一个动态代理后的Mapper
    DemoMapper demoMapper = sqlSession.getMapper(DemoMapper.class);
    Map<String, Object> map = new HashMap<>();
    map.put("arg0", "1");
    System.out.println(demoMapper.selectAll("1", "测试"));
    sqlSession.close();
  }
}
