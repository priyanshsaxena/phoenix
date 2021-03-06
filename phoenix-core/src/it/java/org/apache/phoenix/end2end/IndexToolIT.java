/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.phoenix.end2end;

import static org.apache.phoenix.util.TestUtil.TEST_PROPERTIES;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

import org.apache.hadoop.conf.Configuration;
import org.apache.phoenix.mapreduce.index.IndexTool;
import org.apache.phoenix.query.BaseTest;
import org.apache.phoenix.query.QueryServices;
import org.apache.phoenix.query.QueryServicesOptions;
import org.apache.phoenix.util.PropertiesUtil;
import org.apache.phoenix.util.QueryUtil;
import org.apache.phoenix.util.ReadOnlyProps;
import org.apache.phoenix.util.SchemaUtil;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

@RunWith(Parameterized.class)
@Category(NeedsOwnMiniClusterTest.class)
public class IndexToolIT extends BaseTest {

    private final boolean localIndex;
    private final boolean transactional;
    private final boolean directApi;
    private final String tableDDLOptions;
    private final boolean mutable;
    private final boolean useSnapshot;

    public IndexToolIT(boolean transactional, boolean mutable, boolean localIndex,
            boolean directApi, boolean useSnapshot) {
        this.localIndex = localIndex;
        this.transactional = transactional;
        this.directApi = directApi;
        this.mutable = mutable;
        this.useSnapshot = useSnapshot;
        StringBuilder optionBuilder = new StringBuilder();
        if (!mutable) {
            optionBuilder.append(" IMMUTABLE_ROWS=true ");
        }
        if (transactional) {
            if (!(optionBuilder.length() == 0)) {
                optionBuilder.append(",");
            }
            optionBuilder.append(" TRANSACTIONAL=true ");
        }
        optionBuilder.append(" SPLIT ON(1,2)");
        this.tableDDLOptions = optionBuilder.toString();
    }

    @BeforeClass
    public static void doSetup() throws Exception {
        Map<String, String> serverProps = Maps.newHashMapWithExpectedSize(2);
        serverProps.put(QueryServices.EXTRA_JDBC_ARGUMENTS_ATTRIB,
            QueryServicesOptions.DEFAULT_EXTRA_JDBC_ARGUMENTS);
        Map<String, String> clientProps = Maps.newHashMapWithExpectedSize(2);
        clientProps.put(QueryServices.TRANSACTIONS_ENABLED, Boolean.TRUE.toString());
        clientProps.put(QueryServices.FORCE_ROW_KEY_ORDER_ATTRIB, Boolean.TRUE.toString());
        setUpTestDriver(new ReadOnlyProps(serverProps.entrySet().iterator()),
            new ReadOnlyProps(clientProps.entrySet().iterator()));
    }

    @Parameters(
            name = "transactional = {0} , mutable = {1} , localIndex = {2}, directApi = {3}, useSnapshot = {4}")
    public static Collection<Boolean[]> data() {
        List<Boolean[]> list = Lists.newArrayListWithExpectedSize(16);
        boolean[] Booleans = new boolean[] { false, true };
        for (boolean transactional : Booleans) {
            for (boolean mutable : Booleans) {
                for (boolean localIndex : Booleans) {
                    for (boolean directApi : Booleans) {
                        for (boolean useSnapshot : Booleans) {
                            list.add(new Boolean[] { transactional, mutable, localIndex, directApi, useSnapshot });
                        }
                    }
                }
            }
        }
        return list;
    }

    @Test
    public void testSecondaryIndex() throws Exception {
        String schemaName = generateUniqueName();
        String dataTableName = generateUniqueName();
        String dataTableFullName = SchemaUtil.getTableName(schemaName, dataTableName);
        String indexTableName = generateUniqueName();
        String indexTableFullName = SchemaUtil.getTableName(schemaName, indexTableName);
        Properties props = PropertiesUtil.deepCopy(TEST_PROPERTIES);
        Connection conn = DriverManager.getConnection(getUrl(), props);
        try {
            String stmString1 =
                    "CREATE TABLE " + dataTableFullName
                            + " (ID INTEGER NOT NULL PRIMARY KEY, NAME VARCHAR, ZIP INTEGER) "
                            + tableDDLOptions;
            conn.createStatement().execute(stmString1);
            String upsertQuery = String.format("UPSERT INTO %s VALUES(?, ?, ?)", dataTableFullName);
            PreparedStatement stmt1 = conn.prepareStatement(upsertQuery);

            // insert two rows
            upsertRow(stmt1, 1);
            upsertRow(stmt1, 2);
            conn.commit();

            if (transactional) {
                // insert two rows in another connection without committing so that they are not
                // visible to other transactions
                try (Connection conn2 = DriverManager.getConnection(getUrl(), props)) {
                    conn2.setAutoCommit(false);
                    PreparedStatement stmt2 = conn2.prepareStatement(upsertQuery);
                    upsertRow(stmt2, 5);
                    upsertRow(stmt2, 6);
                    ResultSet rs =
                            conn.createStatement()
                                    .executeQuery("SELECT count(*) from " + dataTableFullName);
                    assertTrue(rs.next());
                    assertEquals("Unexpected row count ", 2, rs.getInt(1));
                    assertFalse(rs.next());
                    rs =
                            conn2.createStatement()
                                    .executeQuery("SELECT count(*) from " + dataTableFullName);
                    assertTrue(rs.next());
                    assertEquals("Unexpected row count ", 4, rs.getInt(1));
                    assertFalse(rs.next());
                }
            }

            String stmtString2 =
                    String.format(
                        "CREATE %s INDEX %s ON %s  (LPAD(UPPER(NAME),8,'x')||'_xyz') ASYNC ",
                        (localIndex ? "LOCAL" : ""), indexTableName, dataTableFullName);
            conn.createStatement().execute(stmtString2);

            // verify rows are fetched from data table.
            String selectSql =
                    String.format(
                        "SELECT ID FROM %s WHERE LPAD(UPPER(NAME),8,'x')||'_xyz' = 'xxUNAME2_xyz'",
                        dataTableFullName);
            ResultSet rs = conn.createStatement().executeQuery("EXPLAIN " + selectSql);
            String actualExplainPlan = QueryUtil.getExplainPlan(rs);

            // assert we are pulling from data table.
            assertEquals(String.format(
                "CLIENT PARALLEL 1-WAY FULL SCAN OVER %s\n"
                        + "    SERVER FILTER BY (LPAD(UPPER(NAME), 8, 'x') || '_xyz') = 'xxUNAME2_xyz'",
                dataTableFullName), actualExplainPlan);

            rs = stmt1.executeQuery(selectSql);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertFalse(rs.next());
            conn.commit();

            // run the index MR job.
            runIndexTool(directApi, useSnapshot, schemaName, dataTableName, indexTableName);

            // insert two more rows
            upsertRow(stmt1, 3);
            upsertRow(stmt1, 4);
            conn.commit();

            // assert we are pulling from index table.
            rs = conn.createStatement().executeQuery("EXPLAIN " + selectSql);
            actualExplainPlan = QueryUtil.getExplainPlan(rs);
            assertExplainPlan(localIndex, actualExplainPlan, dataTableFullName, indexTableFullName);

            rs = conn.createStatement().executeQuery(selectSql);
            assertTrue(rs.next());
            assertEquals(2, rs.getInt(1));
            assertFalse(rs.next());
        } finally {
            conn.close();
        }
    }

    public static void assertExplainPlan(boolean localIndex, String actualExplainPlan,
            String dataTableFullName, String indexTableFullName) {
        String expectedExplainPlan;
        if (localIndex) {
            expectedExplainPlan = String.format(" RANGE SCAN OVER %s [1,", dataTableFullName);
        } else {
            expectedExplainPlan = String.format(" RANGE SCAN OVER %s", indexTableFullName);
        }
        assertTrue(actualExplainPlan + "\n expected to contain \n" + expectedExplainPlan,
            actualExplainPlan.contains(expectedExplainPlan));
    }

    public static String[] getArgValues(boolean directApi, boolean useSnapshot, String schemaName,
            String dataTable, String indxTable) {
        final List<String> args = Lists.newArrayList();
        if (schemaName != null) {
            args.add("-s");
            args.add(schemaName);
        }
        args.add("-dt");
        args.add(dataTable);
        args.add("-it");
        args.add(indxTable);
        if (directApi) {
            args.add("-direct");
            // Need to run this job in foreground for the test to be deterministic
            args.add("-runfg");
        }

        if (useSnapshot) {
            args.add("-snap");
        }

        args.add("-op");
        args.add("/tmp/" + UUID.randomUUID().toString());
        return args.toArray(new String[0]);
    }

    public static void upsertRow(PreparedStatement stmt, int i) throws SQLException {
        // insert row
        stmt.setInt(1, i);
        stmt.setString(2, "uname" + String.valueOf(i));
        stmt.setInt(3, 95050 + i);
        stmt.executeUpdate();
    }

    public static void runIndexTool(boolean directApi, boolean useSnapshot, String schemaName,
            String dataTableName, String indexTableName) throws Exception {
        IndexTool indexingTool = new IndexTool();
        Configuration conf = new Configuration(getUtility().getConfiguration());
        conf.set(QueryServices.TRANSACTIONS_ENABLED, Boolean.TRUE.toString());
        indexingTool.setConf(conf);
        final String[] cmdArgs =
                getArgValues(directApi, useSnapshot, schemaName, dataTableName, indexTableName);
        int status = indexingTool.run(cmdArgs);
        assertEquals(0, status);
    }
}
