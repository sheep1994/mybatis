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

import com.mysql.jdbc.Driver;

import java.sql.*;

/**
 * @program: TestJdbc
 * @author: guobing
 * @description: jdbc连接
 * @create: 2019-09-18 09:41
 */
public class TestJdbc {

  static {
    try {
      Class.forName(Driver.class.getName());
    } catch (ClassNotFoundException e) {
      e.printStackTrace();
    }
  }

  public static void main(String[] args) throws SQLException {
    Connection connection = DriverManager.getConnection("jdbc:mysql://localhost:3306/test", "root", "welcome123");

    PreparedStatement ps = connection.prepareStatement("select * from student where id = ?");

    ps.setInt(1, 1);
    ResultSet resultSet = ps.executeQuery();
    while (resultSet.next()) {
      String id = resultSet.getMetaData().getColumnName(1);
      String name = resultSet.getMetaData().getColumnName(2);
      System.out.println(id + " : " + resultSet.getString(1));
      System.out.println(name + " : " + resultSet.getString(2));
    }

    resultSet.close();
    ps.close();
    connection.close();

  }
}
