package io.mycat.route.util;

import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.statement.SQLCharacterDataType;
import com.alibaba.druid.sql.ast.statement.SQLColumnDefinition;
import com.alibaba.druid.sql.ast.statement.SQLCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.wall.spi.WallVisitorUtils;
import com.google.common.base.Strings;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import io.mycat.MycatServer;
import io.mycat.backend.datasource.PhysicalDBNode;
import io.mycat.backend.datasource.PhysicalDBPool;
import io.mycat.backend.datasource.PhysicalDatasource;
import io.mycat.backend.mysql.nio.handler.FetchStoreNodeOfChildTableHandler;
import io.mycat.cache.LayerCachePool;
import io.mycat.config.ErrorCode;
import io.mycat.config.MycatConfig;
import io.mycat.config.model.SchemaConfig;
import io.mycat.config.model.TableConfig;
import io.mycat.config.model.rule.RuleConfig;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.route.SessionSQLPair;
import io.mycat.route.function.AbstractPartitionAlgorithm;
import io.mycat.route.function.SlotFunction;
import io.mycat.route.parser.druid.DruidShardingParseInfo;
import io.mycat.route.parser.druid.RouteCalculateUnit;
import io.mycat.server.ServerConnection;
import io.mycat.server.parser.ServerParse;
import io.mycat.sqlengine.mpp.ColumnRoutePair;
import io.mycat.sqlengine.mpp.LoadData;
import io.mycat.util.StringUtil;

/**
 * ???ServerRouterUtil??????????????????????????????????????????????????????
 * @author wang.dw
 *
 */
public class RouterUtil {

	private static final Logger LOGGER = LoggerFactory.getLogger(RouterUtil.class);

	/**
	 * ????????????????????????????????????
	 *
	 * @param stmt		 ????????????
	 * @param schema  	????????????
	 * @return 			????????????
	 * @author mycat
     *
     * @modification ????????????schema?????????
     * @date 2016/12/29
     * @modifiedBy Hash Zhang
     *
	 */
	public static String removeSchema(String stmt, String schema) {
        final String upStmt = stmt.toUpperCase();
        final String upSchema = schema.toUpperCase() + ".";
        final String upSchema2 = new StringBuilder("`").append(schema.toUpperCase()).append("`.").toString();
        int strtPos = 0;
        int indx = 0;

        int indx1 = upStmt.indexOf(upSchema, strtPos);
        int indx2 = upStmt.indexOf(upSchema2, strtPos);
        boolean flag = indx1 < indx2 ? indx1 == -1 : indx2 != -1;
        indx = !flag ? indx1 > 0 ? indx1 : indx2 : indx2 > 0 ? indx2 : indx1;
        if (indx < 0) {
            return stmt;
        }

        int firstE = upStmt.indexOf("'");
        int endE = upStmt.lastIndexOf("'");

        StringBuilder sb = new StringBuilder();
        while (indx > 0) {
            sb.append(stmt.substring(strtPos, indx));

            if (flag) {
                strtPos = indx + upSchema2.length();
            } else {
                strtPos = indx + upSchema.length();
            }
            if (indx > firstE && indx < endE && countChar(stmt, indx) % 2 == 1) {
                sb.append(stmt.substring(indx, indx + schema.length() + 1));
            }
            indx1 = upStmt.indexOf(upSchema, strtPos);
            indx2 = upStmt.indexOf(upSchema2, strtPos);
            flag = indx1 < indx2 ? indx1 == -1 : indx2 != -1;
            indx = !flag ? indx1 > 0 ? indx1 : indx2 : indx2 > 0 ? indx2 : indx1;
        }
        sb.append(stmt.substring(strtPos));
        return sb.toString();
    }

	private static int countChar(String sql,int end)
	{
		int count=0;
		boolean skipChar = false;
		for (int i = 0; i < end; i++) {
			if(sql.charAt(i)=='\'' && !skipChar) {
				count++;
				skipChar = false;
			}else if( sql.charAt(i)=='\\'){
				skipChar = true;
			}else{
				skipChar = false;
			}
		}
		return count;
	}

	/**
	 * ?????????????????????????????????
	 *
	 * @param rrs		          ??????????????????
	 * @param dataNode  	?????????????????????
	 * @param stmt   		????????????
	 * @return 				??????????????????
	 *
	 * @author mycat
	 */
	public static RouteResultset routeToSingleNode(RouteResultset rrs,
			String dataNode, String stmt) {
		if (dataNode == null) {
			return rrs;
		}
		RouteResultsetNode[] nodes = new RouteResultsetNode[1];
		nodes[0] = new RouteResultsetNode(dataNode, rrs.getSqlType(), stmt);//rrs.getStatement()
		nodes[0].setSource(rrs);
		rrs.setNodes(nodes);
		rrs.setFinishedRoute(true);
		if(rrs.getDataNodeSlotMap().containsKey(dataNode)){
			nodes[0].setSlot(rrs.getDataNodeSlotMap().get(dataNode));
		}
		if (rrs.getCanRunInReadDB() != null) {
			nodes[0].setCanRunInReadDB(rrs.getCanRunInReadDB());
		}
		if(rrs.getRunOnSlave() != null){
			nodes[0].setRunOnSlave(rrs.getRunOnSlave());
		}

		return rrs;
	}



	/**
	 * ??????DDL??????
	 *
	 * @return RouteResultset
	 * @author aStoneGod
	 */
	public static RouteResultset routeToDDLNode(RouteResultset rrs, int sqlType, String stmt,SchemaConfig schema) throws SQLSyntaxErrorException {
		stmt = getFixedSql(stmt);
		String tablename = "";
		final String upStmt = stmt.toUpperCase();
		if(upStmt.startsWith("CREATE")){
			if (upStmt.contains("CREATE INDEX ")){
				tablename = RouterUtil.getTableName(stmt, RouterUtil.getCreateIndexPos(upStmt, 0));
			}else {
				tablename = RouterUtil.getTableName(stmt, RouterUtil.getCreateTablePos(upStmt, 0));
			}
		}else if(upStmt.startsWith("DROP")){
			if (upStmt.contains("DROP INDEX ")){
				tablename = RouterUtil.getTableName(stmt, RouterUtil.getDropIndexPos(upStmt, 0));
			}else {
				tablename = RouterUtil.getTableName(stmt, RouterUtil.getDropTablePos(upStmt, 0));
			}
		}else if(upStmt.startsWith("ALTER")){
			tablename = RouterUtil.getTableName(stmt, RouterUtil.getAlterTablePos(upStmt, 0));
		}else if (upStmt.startsWith("TRUNCATE")){
			tablename = RouterUtil.getTableName(stmt, RouterUtil.getTruncateTablePos(upStmt, 0));
		}
		tablename = tablename.toUpperCase();

		if (schema.getTables().containsKey(tablename)){
			if(ServerParse.DDL==sqlType){
				List<String> dataNodes = new ArrayList<>();
				Map<String, TableConfig> tables = schema.getTables();
				TableConfig tc=tables.get(tablename);
				if (tables != null && (tc  != null)) {
					dataNodes = tc.getDataNodes();
				}
				boolean isSlotFunction= tc.getRule() != null && tc.getRule().getRuleAlgorithm() instanceof SlotFunction;
				Iterator<String> iterator1 = dataNodes.iterator();
				int nodeSize = dataNodes.size();
				RouteResultsetNode[] nodes = new RouteResultsetNode[nodeSize];
				 if(isSlotFunction){
					 stmt=changeCreateTable(schema,tablename,stmt);
				 }
				for(int i=0;i<nodeSize;i++){
					String name = iterator1.next();
					nodes[i] = new RouteResultsetNode(name, sqlType, stmt);
					nodes[i].setSource(rrs);
					if(rrs.getDataNodeSlotMap().containsKey(name)){
						nodes[i].setSlot(rrs.getDataNodeSlotMap().get(name));
					}  else if(isSlotFunction){
						nodes[i].setSlot(-1);
					}
				}
				rrs.setNodes(nodes);
			}
			return rrs;
		}else if(schema.getDataNode()!=null){		//????????????ddl
			RouteResultsetNode[] nodes = new RouteResultsetNode[1];
			nodes[0] = new RouteResultsetNode(schema.getDataNode(), sqlType, stmt);
			nodes[0].setSource(rrs);
			rrs.setNodes(nodes);
			return rrs;
		}
		//both tablename and defaultnode null
		LOGGER.error("table not in schema----"+tablename);
		throw new SQLSyntaxErrorException("op table not in schema----"+tablename);
	}

	private  static String changeCreateTable(SchemaConfig schema,String tableName,String sql) {
		if (schema.getTables().containsKey(tableName)) {
			MySqlStatementParser parser = new MySqlStatementParser(sql);
			SQLStatement insertStatement = parser.parseStatement();
			if (insertStatement instanceof MySqlCreateTableStatement) {
				TableConfig tableConfig = schema.getTables().get(tableName);
				AbstractPartitionAlgorithm algorithm = tableConfig.getRule().getRuleAlgorithm();
				if (algorithm instanceof SlotFunction) {
					SQLColumnDefinition column = new SQLColumnDefinition();
					column.setDataType(new SQLCharacterDataType("int"));
					column.setName(new SQLIdentifierExpr("_slot"));
					column.setComment(new SQLCharExpr("??????????????????slot,????????????"));
					((SQLCreateTableStatement) insertStatement).getTableElementList().add(column);
					return insertStatement.toString();

				}
			}

		}
		return sql;
	}

	/**
	 * ??????SQL
	 *
	 * @param stmt   ????????????
	 * @return 		 ?????????SQL
	 * @author AStoneGod
	 */
	public static String getFixedSql(String stmt){
		stmt = stmt.replaceAll("\r\n", " "); //??????\r\n????????? ??? ???????????? rainbow
		return stmt = stmt.trim(); //.toUpperCase();
	}

	/**
	 * ??????table??????
	 *
	 * @param stmt  	????????????
	 * @param repPos	?????????????????????
	 * @return ??????
	 * @author AStoneGod
	 */
	public static String getTableName(String stmt, int[] repPos) {
		int startPos = repPos[0];
		int secInd = stmt.indexOf(' ', startPos + 1);
		if (secInd < 0) {
			secInd = stmt.length();
		}
		int thiInd = stmt.indexOf('(',secInd+1);
		if (thiInd < 0) {
			thiInd = stmt.length();
		}
		repPos[1] = secInd;
		String tableName = "";
		if (stmt.toUpperCase().startsWith("DESC")||stmt.toUpperCase().startsWith("DESCRIBE")){
			tableName = stmt.substring(startPos, thiInd).trim();
		}else {
			tableName = stmt.substring(secInd, thiInd).trim();
		}

		//ALTER TABLE
		if (tableName.contains(" ")){
			tableName = tableName.substring(0,tableName.indexOf(" "));
		}
		int ind2 = tableName.indexOf('.');
		if (ind2 > 0) {
			tableName = tableName.substring(ind2 + 1);
		}
		return tableName;
	}


	/**
	 * ??????show??????table??????
	 *
	 * @param stmt	        ????????????
	 * @param repPos   ?????????????????????
	 * @return ??????
	 * @author AStoneGod
	 */
	public static String getShowTableName(String stmt, int[] repPos) {
		int startPos = repPos[0];
		int secInd = stmt.indexOf(' ', startPos + 1);
		if (secInd < 0) {
			secInd = stmt.length();
		}

		repPos[1] = secInd;
		String tableName = stmt.substring(startPos, secInd).trim();

		int ind2 = tableName.indexOf('.');
		if (ind2 > 0) {
			tableName = tableName.substring(ind2 + 1);
		}
		return tableName;
	}

	/**
	 * ????????????????????????????????????????????????????????????
	 *
	 * @param upStmt     ????????????
	 * @param start      ????????????
	 * @return int[]	  ??????????????????????????????
	 *
	 * @author mycat
	 *
	 * @modification ??????????????????????????????IF NOT EXISTS????????????
	 * @date 2016/12/8
	 * @modifiedBy Hash Zhang
	 */
	public static int[] getCreateTablePos(String upStmt, int start) {
		String token1 = "CREATE ";
		String token2 = " TABLE ";
		String token3 = " EXISTS ";
		int createInd = upStmt.indexOf(token1, start);
		int tabInd1 = upStmt.indexOf(token2, start);
		int tabInd2 = upStmt.indexOf(token3, tabInd1);
		// ?????????CREATE?????????TABLE??????CREATE????????????TABLE???????????????
		if (createInd >= 0 && tabInd2 > 0 && tabInd2 > createInd) {
			return new int[] { tabInd2, token3.length() };
		} else if(createInd >= 0 && tabInd1 > 0 && tabInd1 > createInd) {
			return new int[] { tabInd1, token2.length() };
		} else {
			return new int[] { -1, token2.length() };// ???????????????????????????????????????????????????-1??????????????????
		}
	}

	/**
	 * ????????????????????????????????????????????????????????????
	 *
	 * @param upStmt
	 *            ????????????
	 * @param start
	 *            ????????????
	 * @return int[]??????????????????????????????
	 * @author aStoneGod
	 */
	public static int[] getCreateIndexPos(String upStmt, int start) {
		String token1 = "CREATE ";
		String token2 = " INDEX ";
		String token3 = " ON ";
		int createInd = upStmt.indexOf(token1, start);
		int idxInd = upStmt.indexOf(token2, start);
		int onInd = upStmt.indexOf(token3, start);
		// ?????????CREATE?????????INDEX??????CREATE????????????INDEX???????????????, ?????????ON...
		if (createInd >= 0 && idxInd > 0 && idxInd > createInd && onInd > 0 && onInd > idxInd) {
			return new int[] {onInd , token3.length() };
		} else {
			return new int[] { -1, token2.length() };// ???????????????????????????????????????????????????-1??????????????????
		}
	}

	/**
	 * ??????ALTER??????????????????????????????????????????????????????
	 *
	 * @param upStmt   ????????????
	 * @param start    ????????????
	 * @return int[]   ??????????????????????????????
	 * @author aStoneGod
	 */
	public static int[] getAlterTablePos(String upStmt, int start) {
		String token1 = "ALTER ";
		String token2 = " TABLE ";
		int createInd = upStmt.indexOf(token1, start);
		int tabInd = upStmt.indexOf(token2, start);
		// ?????????CREATE?????????TABLE??????CREATE????????????TABLE???????????????
		if (createInd >= 0 && tabInd > 0 && tabInd > createInd) {
			return new int[] { tabInd, token2.length() };
		} else {
			return new int[] { -1, token2.length() };// ???????????????????????????????????????????????????-1??????????????????
		}
	}

	/**
	 * ??????DROP??????????????????????????????????????????????????????
	 *
	 * @param upStmt 	????????????
	 * @param start  	????????????
	 * @return int[]	??????????????????????????????
	 * @author aStoneGod
	 */
	public static int[] getDropTablePos(String upStmt, int start) {
		//?????? if exists??????
		if(upStmt.contains("EXISTS")){
			String token1 = "IF ";
			String token2 = " EXISTS ";
			int ifInd = upStmt.indexOf(token1, start);
			int tabInd = upStmt.indexOf(token2, start);
			if (ifInd >= 0 && tabInd > 0 && tabInd > ifInd) {
				return new int[] { tabInd, token2.length() };
			} else {
				return new int[] { -1, token2.length() };// ???????????????????????????????????????????????????-1??????????????????
			}
		}else {
			String token1 = "DROP ";
			String token2 = " TABLE ";
			int createInd = upStmt.indexOf(token1, start);
			int tabInd = upStmt.indexOf(token2, start);

			if (createInd >= 0 && tabInd > 0 && tabInd > createInd) {
				return new int[] { tabInd, token2.length() };
			} else {
				return new int[] { -1, token2.length() };// ???????????????????????????????????????????????????-1??????????????????
			}
		}
	}


	/**
	 * ??????DROP??????????????????????????????????????????????????????
	 *
	 * @param upStmt
	 *            ????????????
	 * @param start
	 *            ????????????
	 * @return int[]??????????????????????????????
	 * @author aStoneGod
	 */

	public static int[] getDropIndexPos(String upStmt, int start) {
		String token1 = "DROP ";
		String token2 = " INDEX ";
		String token3 = " ON ";
		int createInd = upStmt.indexOf(token1, start);
		int idxInd = upStmt.indexOf(token2, start);
		int onInd = upStmt.indexOf(token3, start);
		// ?????????CREATE?????????INDEX??????CREATE????????????INDEX???????????????, ?????????ON...
		if (createInd >= 0 && idxInd > 0 && idxInd > createInd && onInd > 0 && onInd > idxInd) {
			return new int[] {onInd , token3.length() };
		} else {
			return new int[] { -1, token2.length() };// ???????????????????????????????????????????????????-1??????????????????
		}
	}

	/**
	 * ??????TRUNCATE??????????????????????????????????????????????????????
	 *
	 * @param upStmt    ????????????
	 * @param start     ????????????
	 * @return int[]	??????????????????????????????
	 * @author aStoneGod
	 */
	public static int[] getTruncateTablePos(String upStmt, int start) {
		String token1 = "TRUNCATE ";
		String token2 = " TABLE ";
		int createInd = upStmt.indexOf(token1, start);
		int tabInd = upStmt.indexOf(token2, start);
		// ?????????CREATE?????????TABLE??????CREATE????????????TABLE???????????????
		if (createInd >= 0 && tabInd > 0 && tabInd > createInd) {
			return new int[] { tabInd, token2.length() };
		} else {
			return new int[] { -1, token2.length() };// ???????????????????????????????????????????????????-1??????????????????
		}
	}

	/**
	 * ????????????????????????????????????????????????????????????
	 *
	 * @param upStmt   ????????????
	 * @param start    ????????????
	 * @return int[]   ??????????????????????????????
	 * @author mycat
	 */
	public static int[] getSpecPos(String upStmt, int start) {
		String token1 = " FROM ";
		String token2 = " IN ";
		int tabInd1 = upStmt.indexOf(token1, start);
		int tabInd2 = upStmt.indexOf(token2, start);
		if (tabInd1 > 0) {
			if (tabInd2 < 0) {
				return new int[] { tabInd1, token1.length() };
			}
			return (tabInd1 < tabInd2) ? new int[] { tabInd1, token1.length() }
					: new int[] { tabInd2, token2.length() };
		} else {
			return new int[] { tabInd2, token2.length() };
		}
	}

	/**
	 * ???????????????????????? LIKE???WHERE ?????? ???????????? LIKE???WHERE ??????????????????????????????
	 *
	 * @param upStmt   ??????sql
	 * @param start    ????????????
	 * @return int
	 * @author mycat
	 */
	public static int getSpecEndPos(String upStmt, int start) {
		int tabInd = upStmt.toUpperCase().indexOf(" LIKE ", start);
		if (tabInd < 0) {
			tabInd = upStmt.toUpperCase().indexOf(" WHERE ", start);
		}
		if (tabInd < 0) {
			return upStmt.length();
		}
		return tabInd;
	}

	public static boolean processWithMycatSeq(SchemaConfig schema, int sqlType,
	                                          String origSQL, ServerConnection sc) {
		// check if origSQL is with global sequence
		// @micmiu it is just a simple judgement
		//?????????????????????????????????insert into table1(id,name) values(next value for MYCATSEQ_GLOBAL,???test???);
		// edit by dingw,??????mycatseq_ ???????????????ServerConnection???373??????????????????????????????????????????????????????????????????
		if (origSQL.indexOf(" MYCATSEQ_") != -1 || origSQL.indexOf("mycatseq_") != -1) {
			processSQL(sc,schema,origSQL,sqlType);
			return true;
		}
		return false;
	}

	public static void processSQL(ServerConnection sc,SchemaConfig schema,String sql,int sqlType){
//		int sequenceHandlerType = MycatServer.getInstance().getConfig().getSystem().getSequnceHandlerType();
		final SessionSQLPair sessionSQLPair = new SessionSQLPair(sc.getSession2(), schema, sql, sqlType);
//      modify by yanjunli  ????????????????????????????????????????????????????????????,????????????????????????  begin		
//		MycatServer.getInstance().getSequnceProcessor().addNewSql(sessionSQLPair);
        MycatServer.getInstance().getSequenceExecutor().execute(new Runnable() {
				@Override
				public void run() {
					MycatServer.getInstance().getSequnceProcessor().executeSeq(sessionSQLPair);
				}
		 });
//      modify   ????????????????????????????????????????????????????????????,????????????????????????  end
//		}
	}

	public static boolean processInsert(SchemaConfig schema, int sqlType,
	                                    String origSQL, ServerConnection sc) throws SQLNonTransientException {
		String tableName = StringUtil.getTableName(origSQL).toUpperCase();
		TableConfig tableConfig = schema.getTables().get(tableName);
		boolean processedInsert=false;
		//????????????????????????
		if (null != tableConfig && tableConfig.isAutoIncrement()) {
			String primaryKey = tableConfig.getPrimaryKey();
			processedInsert=processInsert(sc,schema,sqlType,origSQL,tableName,primaryKey);
		}
		return processedInsert;
	}

	private static boolean isPKInFields(String origSQL,String primaryKey,int firstLeftBracketIndex,int firstRightBracketIndex){

		if (primaryKey == null) {
			throw new RuntimeException("please make sure the primaryKey's config is not null in schemal.xml");
		}

		boolean isPrimaryKeyInFields = false;
		String upperSQL = origSQL.substring(firstLeftBracketIndex, firstRightBracketIndex + 1).toUpperCase();
		for (int pkOffset = 0, primaryKeyLength = primaryKey.length(), pkStart = 0;;) {
			pkStart = upperSQL.indexOf(primaryKey, pkOffset);
			if (pkStart >= 0 && pkStart < firstRightBracketIndex) {
				char pkSide = upperSQL.charAt(pkStart - 1);
				if (pkSide <= ' ' || pkSide == '`' || pkSide == ',' || pkSide == '(') {
					pkSide = upperSQL.charAt(pkStart + primaryKey.length());
					isPrimaryKeyInFields = pkSide <= ' ' || pkSide == '`' || pkSide == ',' || pkSide == ')';
				}
				if (isPrimaryKeyInFields) {
					break;
				}
				pkOffset = pkStart + primaryKeyLength;
			} else {
				break;
			}
		}
		return isPrimaryKeyInFields;
	}

	public static boolean processInsert(ServerConnection sc,SchemaConfig schema,
			int sqlType,String origSQL,String tableName,String primaryKey) throws SQLNonTransientException {

		int firstLeftBracketIndex = origSQL.indexOf("(");
		int firstRightBracketIndex = origSQL.indexOf(")");
		String upperSql = origSQL.toUpperCase();
		int valuesIndex = upperSql.indexOf("VALUES");
		int selectIndex = upperSql.indexOf("SELECT");
		int fromIndex = upperSql.indexOf("FROM");
		//??????insert into table1 select * from table2??????
		if(firstLeftBracketIndex < 0) {
			String msg = "invalid sql:" + origSQL;
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
		//??????????????????
		if(selectIndex > 0 &&fromIndex>0&&selectIndex>firstRightBracketIndex&&valuesIndex<0) {
			String msg = "multi insert not provided" ;
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
		//??????????????????????????????????????????MyCat??????????????????????????????
		if(valuesIndex + "VALUES".length() <= firstLeftBracketIndex) {
			throw new SQLSyntaxErrorException("insert must provide ColumnList");
		}
		//?????????????????????????????????fields??????????????????????????????
		boolean processedInsert=!isPKInFields(origSQL,primaryKey,firstLeftBracketIndex,firstRightBracketIndex);
		if(processedInsert){
			handleBatchInsert(sc, schema, sqlType,origSQL, valuesIndex,tableName,primaryKey);
		}
		return processedInsert;
	}

	public static List<String> handleBatchInsert(String origSQL, int valuesIndex) {
		List<String> handledSQLs = new LinkedList<>();
		String prefix = origSQL.substring(0, valuesIndex + "VALUES".length());
		String values = origSQL.substring(valuesIndex + "VALUES".length());
		int flag = 0;
		StringBuilder currentValue = new StringBuilder();
		currentValue.append(prefix);
		for (int i = 0; i < values.length(); i++) {
			char j = values.charAt(i);
			if (j == '(' && flag == 0) {
				flag = 1;
				currentValue.append(j);
			} else if (j == '\"' && flag == 1) {
				flag = 2;
				currentValue.append(j);
			} else if (j == '\'' && flag == 1) {
				flag = 3;
				currentValue.append(j);
			} else if (j == '\\' && flag == 2) {
				flag = 4;
				currentValue.append(j);
			} else if (j == '\\' && flag == 3) {
				flag = 5;
				currentValue.append(j);
			} else if (flag == 4) {
				flag = 2;
				currentValue.append(j);
			} else if (flag == 5) {
				flag = 3;
				currentValue.append(j);
			} else if (j == '\"' && flag == 2) {
				flag = 1;
				currentValue.append(j);
			} else if (j == '\'' && flag == 3) {
				flag = 1;
				currentValue.append(j);
			} else if (j == ')' && flag == 1) {
				flag = 0;
				currentValue.append(j);
				handledSQLs.add(currentValue.toString());
				currentValue = new StringBuilder();
				currentValue.append(prefix);
			} else if (j == ',' && flag == 0) {
				continue;
			} else {
				currentValue.append(j);
			}
		}
		return handledSQLs;
	}
	
	  /**
	  * ?????????????????????????????????fields??????SQL????????????????????????hotnews?????????id?????????????????????
	  * insert into hotnews(title) values('aaa');
	  * ??????????????????
	  * insert into hotnews(id, title) values(next value for MYCATSEQ_hotnews,'aaa');
	  */
    public static void handleBatchInsert(ServerConnection sc, SchemaConfig schema,
            int sqlType,String origSQL, int valuesIndex,String tableName, String primaryKey) {
    	
    	final String pk = "\\("+primaryKey+",";
        final String mycatSeqPrefix = "(next value for MYCATSEQ_"+tableName.toUpperCase()+",";
    	
    	/*"VALUES".length() ==6 */
        String prefix = origSQL.substring(0, valuesIndex + 6);
        String values = origSQL.substring(valuesIndex + 6);
        
        prefix = prefix.replaceFirst("\\(", pk);
        values = values.replaceFirst("\\(", mycatSeqPrefix);
        values =Pattern.compile(",\\s*\\(").matcher(values).replaceAll(","+mycatSeqPrefix);
        processSQL(sc, schema,prefix+values, sqlType);
    }

	public static RouteResultset routeToMultiNode(boolean cache,RouteResultset rrs, Collection<String> dataNodes, String stmt) {
		RouteResultsetNode[] nodes = new RouteResultsetNode[dataNodes.size()];
		int i = 0;
		RouteResultsetNode node;
		for (String dataNode : dataNodes) {
			node = new RouteResultsetNode(dataNode, rrs.getSqlType(), stmt);
			node.setSource(rrs);
			if(rrs.getDataNodeSlotMap().containsKey(dataNode)){
				node.setSlot(rrs.getDataNodeSlotMap().get(dataNode));
			}
			if (rrs.getCanRunInReadDB() != null) {
				node.setCanRunInReadDB(rrs.getCanRunInReadDB());
			}
			if(rrs.getRunOnSlave() != null){
				nodes[0].setRunOnSlave(rrs.getRunOnSlave());
			}
			nodes[i++] = node;
		}
		rrs.setCacheAble(cache);
		rrs.setNodes(nodes);
		return rrs;
	}

	public static RouteResultset routeToMultiNode(boolean cache, RouteResultset rrs, Collection<String> dataNodes,
			String stmt, boolean isGlobalTable) {

		rrs = routeToMultiNode(cache, rrs, dataNodes, stmt);
		rrs.setGlobalTable(isGlobalTable);
		return rrs;
	}

	public static void routeForTableMeta(RouteResultset rrs,
			SchemaConfig schema, String tableName, String sql) {
		String dataNode = null;
		if (isNoSharding(schema,tableName)) {//?????????????????????schema?????????dataNode
			dataNode = schema.getDataNode();
		} else {
			dataNode = getMetaReadDataNode(schema, tableName);
		}

		RouteResultsetNode[] nodes = new RouteResultsetNode[1];
		nodes[0] = new RouteResultsetNode(dataNode, rrs.getSqlType(), sql);
		nodes[0].setSource(rrs);
		if(rrs.getDataNodeSlotMap().containsKey(dataNode)){
			nodes[0].setSlot(rrs.getDataNodeSlotMap().get(dataNode));
		}
		if (rrs.getCanRunInReadDB() != null) {
			nodes[0].setCanRunInReadDB(rrs.getCanRunInReadDB());
		}
		if(rrs.getRunOnSlave() != null){
			nodes[0].setRunOnSlave(rrs.getRunOnSlave());
		}
		rrs.setNodes(nodes);
	}

	/**
	 * ????????????????????????????????????
	 *
	 * @param schema     ????????????
	 * @param table      ??????
	 * @return 			  ????????????
	 * @author mycat
	 */
	private static String getMetaReadDataNode(SchemaConfig schema,
			String table) {
		// Table???????????????????????????????????????schema
		table = table.toUpperCase();
		String dataNode = null;
		Map<String, TableConfig> tables = schema.getTables();
		TableConfig tc;
		if (tables != null && (tc = tables.get(table)) != null) {
			dataNode = getAliveRandomDataNode(tc);
		}
		return dataNode;
	}
	
	/**
	 * ??????getRandomDataNode?????????????????????????????????.
	 * @param tc
	 * @return
	 */
	private static String getAliveRandomDataNode(TableConfig tc) {
		List<String> randomDns = tc.getDataNodes();

		MycatConfig mycatConfig = MycatServer.getInstance().getConfig();
		if (mycatConfig != null) {
			for (String randomDn : randomDns) {
				PhysicalDBNode physicalDBNode = mycatConfig.getDataNodes().get(randomDn);
				if (physicalDBNode != null) {
					if (physicalDBNode.getDbPool().getSource().isAlive()) {
						for (PhysicalDBPool pool : MycatServer.getInstance().getConfig().getDataHosts().values()) {
							PhysicalDatasource source = pool.getSource();
							if (source.getHostConfig().containDataNode(randomDn) && pool.getSource().isAlive()) {
								return randomDn;
							}
						}
					}
				}
			}
		}

		// all fail return default
		return tc.getRandomDataNode();
	}

	@Deprecated
    private static String getRandomDataNode(TableConfig tc) {
        //??????????????????????????????????????????????????????
        //????????????????????? dataHost
        String randomDn = tc.getRandomDataNode();
        MycatConfig mycatConfig = MycatServer.getInstance().getConfig();
        if (mycatConfig != null) {
            PhysicalDBNode physicalDBNode = mycatConfig.getDataNodes().get(randomDn);
            if (physicalDBNode != null) {
                if (physicalDBNode.getDbPool().getSource().isAlive()) {
                    for (PhysicalDBPool pool : MycatServer.getInstance()
                            .getConfig()
                            .getDataHosts()
                            .values()) {
                        if (pool.getSource().getHostConfig().containDataNode(randomDn)) {
                            continue;
                        }

                        if (pool.getSource().isAlive()) {
                            return pool.getSource().getHostConfig().getRandomDataNode();
                        }
                    }
                }
            }
        }

        //all fail return default
        return randomDn;
    }

	/**
	 * ?????? ER??????????????????????????????
	 *
	 * @param stmt            ???????????????
	 * @param rrs      		     ??????????????????
	 * @param tc	      	     ?????????
	 * @param joinKeyVal      ????????????
	 * @return RouteResultset(??????????????????)	 *
	 * @throws SQLNonTransientException???IllegalShardingColumnValueException
	 * @author mycat
	 */

	public static RouteResultset routeByERParentKey(ServerConnection sc,SchemaConfig schema,
                                                    int sqlType,String stmt,
			RouteResultset rrs, TableConfig tc, String joinKeyVal)
			throws SQLNonTransientException {

		// only has one parent level and ER parent key is parent
		// table's partition key
		if (tc.isSecondLevel()
				//??????????????????????????????????????????????????????
				&& tc.getParentTC().getPartitionColumn()
						.equals(tc.getParentKey())) { // using
														// parent
														// rule to
														// find
														// datanode
			Set<ColumnRoutePair> parentColVal = new HashSet<ColumnRoutePair>(1);
			ColumnRoutePair pair = new ColumnRoutePair(joinKeyVal);
			parentColVal.add(pair);
			Set<String> dataNodeSet = ruleCalculate(tc.getParentTC(), parentColVal,rrs.getDataNodeSlotMap());
			if (dataNodeSet.isEmpty() || dataNodeSet.size() > 1) {
				throw new SQLNonTransientException(
						"parent key can't find  valid datanode ,expect 1 but found: "
								+ dataNodeSet.size());
			}
			String dn = dataNodeSet.iterator().next();
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("found partion node (using parent partion rule directly) for child table to insert  "
						+ dn + " sql :" + stmt);
			}
			return RouterUtil.routeToSingleNode(rrs, dn, stmt);
		}
		return null;
	}

	/**
	 * @return dataNodeIndex -&gt; [partitionKeysValueTuple+]
	 */
	public static Set<String> ruleByJoinValueCalculate(RouteResultset rrs, TableConfig tc,
			Set<ColumnRoutePair> colRoutePairSet) throws SQLNonTransientException {

		String joinValue = "";

		if(colRoutePairSet.size() > 1) {
			LOGGER.warn("joinKey can't have multi Value");
		} else {
			Iterator<ColumnRoutePair> it = colRoutePairSet.iterator();
			ColumnRoutePair joinCol = it.next();
			joinValue = joinCol.colValue;
		}

		Set<String> retNodeSet = new LinkedHashSet<String>();

		Set<String> nodeSet;
		if (tc.isSecondLevel()
				&& tc.getParentTC().getPartitionColumn()
						.equals(tc.getParentKey())) { // using
														// parent
														// rule to
														// find
														// datanode

			nodeSet = ruleCalculate(tc.getParentTC(),colRoutePairSet,rrs.getDataNodeSlotMap());
			if (nodeSet.isEmpty()) {
				throw new SQLNonTransientException(
						"parent key can't find  valid datanode ,expect 1 but found: "
								+ nodeSet.size());
			}
			if (LOGGER.isDebugEnabled()) {
				LOGGER.debug("found partion node (using parent partion rule directly) for child table to insert  "
						+ nodeSet + " sql :" + rrs.getStatement());
			}
			retNodeSet.addAll(nodeSet);

//			for(ColumnRoutePair pair : colRoutePairSet) {
//				nodeSet = ruleCalculate(tc.getParentTC(),colRoutePairSet);
//				if (nodeSet.isEmpty() || nodeSet.size() > 1) {//an exception would be thrown, if sql was executed on more than on sharding
//					throw new SQLNonTransientException(
//							"parent key can't find  valid datanode ,expect 1 but found: "
//									+ nodeSet.size());
//				}
//				String dn = nodeSet.iterator().next();
//				if (LOGGER.isDebugEnabled()) {
//					LOGGER.debug("found partion node (using parent partion rule directly) for child table to insert  "
//							+ dn + " sql :" + rrs.getStatement());
//				}
//				retNodeSet.addAll(nodeSet);
//			}
			return retNodeSet;
		} else {
			retNodeSet.addAll(tc.getParentTC().getDataNodes());
		}

		return retNodeSet;
	}


	/**
	 * @return dataNodeIndex -&gt; [partitionKeysValueTuple+]
	 */
	public static Set<String> ruleCalculate(TableConfig tc,
			Set<ColumnRoutePair> colRoutePairSet,Map<String,Integer>   dataNodeSlotMap)  {
		Set<String> routeNodeSet = new LinkedHashSet<String>();
		String col = tc.getRule().getColumn();
		RuleConfig rule = tc.getRule();
		AbstractPartitionAlgorithm algorithm = rule.getRuleAlgorithm();
		for (ColumnRoutePair colPair : colRoutePairSet) {
			if (colPair.colValue != null) {
				Integer nodeIndx = algorithm.calculate(colPair.colValue);
				if (nodeIndx == null) {
					throw new IllegalArgumentException(
							"can't find datanode for sharding column:" + col
									+ " val:" + colPair.colValue);
				} else {
					String dataNode = tc.getDataNodes().get(nodeIndx);
					routeNodeSet.add(dataNode);
					if(algorithm instanceof SlotFunction) {
						dataNodeSlotMap.put(dataNode,((SlotFunction) algorithm).slotValue());
					}
					colPair.setNodeId(nodeIndx);
				}
			} else if (colPair.rangeValue != null) {
				Integer[] nodeRange = algorithm.calculateRange(
						String.valueOf(colPair.rangeValue.beginValue),
						String.valueOf(colPair.rangeValue.endValue));
				if (nodeRange != null) {
					/**
					 * ???????????? colPair??? nodeid????????????????????????
					 */
					if (nodeRange.length == 0) {
						routeNodeSet.addAll(tc.getDataNodes());
					} else {
						ArrayList<String> dataNodes = tc.getDataNodes();
						String dataNode = null;
						for (Integer nodeId : nodeRange) {
							dataNode = dataNodes.get(nodeId);
							if(algorithm instanceof SlotFunction) {
								dataNodeSlotMap.put(dataNode,((SlotFunction) algorithm).slotValue());
							}
							routeNodeSet.add(dataNode);
						}
					}
				}
			}

		}
		return routeNodeSet;
	}

	/**
	 * ????????????
	 */
	public static RouteResultset tryRouteForTables(SchemaConfig schema, DruidShardingParseInfo ctx,
			RouteCalculateUnit routeUnit, RouteResultset rrs, boolean isSelect, LayerCachePool cachePool)
			throws SQLNonTransientException {

		List<String> tables = ctx.getTables();

		if(schema.isNoSharding()||(tables.size() >= 1&&isNoSharding(schema,tables.get(0)))) {
			return routeToSingleNode(rrs, schema.getDataNode(), ctx.getSql());
		}

		//??????????????????
		if(tables.size() == 1) {
			return RouterUtil.tryRouteForOneTable(schema, ctx, routeUnit, tables.get(0), rrs, isSelect, cachePool);
		}

		Set<String> retNodesSet = new HashSet<String>();
		//??????????????????????????????
		Map<String,Set<String>> tablesRouteMap = new HashMap<String,Set<String>>();

		//???????????????????????????
		Map<String, Map<String, Set<ColumnRoutePair>>> tablesAndConditions = routeUnit.getTablesAndConditions();
		if(tablesAndConditions != null && tablesAndConditions.size() > 0) {
			//?????????????????????
			RouterUtil.findRouteWithcConditionsForTables(schema, rrs, tablesAndConditions, tablesRouteMap, ctx.getSql(), cachePool, isSelect);
			if(rrs.isFinishedRoute()) {
				return rrs;
			}
		}

		//?????????????????????????????????
		for(String tableName : tables) {
			
			TableConfig tableConfig = schema.getTables().get(tableName.toUpperCase());
			
			if(tableConfig == null) {
				//add ????????????????????????????????????????????????????????????????????????
				String alias = ctx.getTableAliasMap().get(tableName);
				if(!StringUtil.isEmpty(alias)){
					tableConfig = schema.getTables().get(alias.toUpperCase());
				}
				
				if(tableConfig == null){
					String msg = "can't find table define in schema "+ tableName + " schema:" + schema.getName();
					LOGGER.warn(msg);
					throw new SQLNonTransientException(msg);
				}
				
			}
			if(tableConfig.isGlobalTable()) {//?????????
				if(tablesRouteMap.get(tableName) == null) {
					tablesRouteMap.put(tableName, new HashSet<String>());
				}
				tablesRouteMap.get(tableName).addAll(tableConfig.getDataNodes());
			} else if(tablesRouteMap.get(tableName) == null) { //???????????????????????????
				tablesRouteMap.put(tableName, new HashSet<String>());
				tablesRouteMap.get(tableName).addAll(tableConfig.getDataNodes());
			}
		}

		boolean isFirstAdd = true;
		for(Map.Entry<String, Set<String>> entry : tablesRouteMap.entrySet()) {
			if(entry.getValue() == null || entry.getValue().size() == 0) {
				throw new SQLNonTransientException("parent key can't find any valid datanode ");
			} else {
				if(isFirstAdd) {
					retNodesSet.addAll(entry.getValue());
					isFirstAdd = false;
				} else {
					retNodesSet.retainAll(entry.getValue());
					if(retNodesSet.size() == 0) {//???????????????????????????
						String errMsg = "invalid route in sql, multi tables found but datanode has no intersection "
								+ " sql:" + ctx.getSql();
						LOGGER.warn(errMsg);
						throw new SQLNonTransientException(errMsg);
					}
				}
			}
		}

		if(retNodesSet != null && retNodesSet.size() > 0) {
			String tableName = tables.get(0);
			TableConfig tableConfig = schema.getTables().get(tableName.toUpperCase());
			if(tableConfig.isDistTable()){
				routeToDistTableNode(tableName,schema, rrs, ctx.getSql(), tablesAndConditions, cachePool, isSelect);
				return rrs;
			}

			if(retNodesSet.size() > 1 && isAllGlobalTable(ctx, schema)) {
				// mulit routes ,not cache route result
				if (isSelect) {
					rrs.setCacheAble(false);
					routeToSingleNode(rrs, retNodesSet.iterator().next(), ctx.getSql());
				}
				else {//delete ????????????????????????
					routeToMultiNode(isSelect, rrs, retNodesSet, ctx.getSql(),true);
				}

			} else {
				routeToMultiNode(isSelect, rrs, retNodesSet, ctx.getSql());
			}

		}
		return rrs;

	}


	/**
	 *
	 * ????????????
	 */
	public static RouteResultset tryRouteForOneTable(SchemaConfig schema, DruidShardingParseInfo ctx,
			RouteCalculateUnit routeUnit, String tableName, RouteResultset rrs, boolean isSelect,
			LayerCachePool cachePool) throws SQLNonTransientException {

		if (isNoSharding(schema, tableName)) {
			return routeToSingleNode(rrs, schema.getDataNode(), ctx.getSql());
		}

		TableConfig tc = schema.getTables().get(tableName);
		if(tc == null) {
			String msg = "can't find table define in schema " + tableName + " schema:" + schema.getName();
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}

		if(tc.isDistTable()){
			return routeToDistTableNode(tableName,schema,rrs,ctx.getSql(), routeUnit.getTablesAndConditions(), cachePool,isSelect);
		}

		if(tc.isGlobalTable()) {//?????????
			if(isSelect) {
				// global select ,not cache route result
				rrs.setCacheAble(false);
				return routeToSingleNode(rrs, getAliveRandomDataNode(tc)/*getRandomDataNode(tc)*/, ctx.getSql());
			} else {//insert into ??????????????????
				return routeToMultiNode(false, rrs, tc.getDataNodes(), ctx.getSql(),true);
			}
		} else {//?????????????????????
			if (!checkRuleRequired(schema, ctx, routeUnit, tc)) {
				throw new IllegalArgumentException("route rule for table "
						+ tc.getName() + " is required: " + ctx.getSql());

			}
			if(tc.getPartitionColumn() == null && !tc.isSecondLevel()) {//???????????????childTable
//				return RouterUtil.routeToSingleNode(rrs, tc.getDataNodes().get(0),ctx.getSql());
				return routeToMultiNode(rrs.isCacheAble(), rrs, tc.getDataNodes(), ctx.getSql());
			} else {
				//??????????????????????????????
				Map<String,Set<String>> tablesRouteMap = new HashMap<String,Set<String>>();
				if(routeUnit.getTablesAndConditions() != null && routeUnit.getTablesAndConditions().size() > 0) {
					RouterUtil.findRouteWithcConditionsForTables(schema, rrs, routeUnit.getTablesAndConditions(), tablesRouteMap, ctx.getSql(), cachePool, isSelect);
					if(rrs.isFinishedRoute()) {
						return rrs;
					}
				}

				if(tablesRouteMap.get(tableName) == null) {
					return routeToMultiNode(rrs.isCacheAble(), rrs, tc.getDataNodes(), ctx.getSql());
				} else {
					return routeToMultiNode(rrs.isCacheAble(), rrs, tablesRouteMap.get(tableName), ctx.getSql());
				}
			}
		}
	}

	private static RouteResultset routeToDistTableNode(String tableName, SchemaConfig schema, RouteResultset rrs,
			String orgSql, Map<String, Map<String, Set<ColumnRoutePair>>> tablesAndConditions,
			LayerCachePool cachePool, boolean isSelect) throws SQLNonTransientException {

		TableConfig tableConfig = schema.getTables().get(tableName);
		if(tableConfig == null) {
			String msg = "can't find table define in schema " + tableName + " schema:" + schema.getName();
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
		if(tableConfig.isGlobalTable()){
			String msg = "can't suport district table  " + tableName + " schema:" + schema.getName() + " for global table ";
			LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
		}
		String partionCol = tableConfig.getPartitionColumn();
//		String primaryKey = tableConfig.getPrimaryKey();
        boolean isLoadData=false;

        Set<String> tablesRouteSet = new HashSet<String>();

        List<String> dataNodes = tableConfig.getDataNodes();
        if(dataNodes.size()>1){
			String msg = "can't suport district table  " + tableName + " schema:" + schema.getName() + " for mutiple dataNode " + dataNodes;
        	LOGGER.warn(msg);
			throw new SQLNonTransientException(msg);
        }
        String dataNode = dataNodes.get(0);

		//?????????????????????????????????
        if(tablesAndConditions.isEmpty()){
        	List<String> subTables = tableConfig.getDistTables();
        	tablesRouteSet.addAll(subTables);
        }

		for(Map.Entry<String, Map<String, Set<ColumnRoutePair>>> entry : tablesAndConditions.entrySet()) {
			boolean isFoundPartitionValue = partionCol != null && entry.getValue().get(partionCol) != null;
			Map<String, Set<ColumnRoutePair>> columnsMap = entry.getValue();

			Set<ColumnRoutePair> partitionValue = columnsMap.get(partionCol);
			if(partitionValue == null || partitionValue.size() == 0) {
				tablesRouteSet.addAll(tableConfig.getDistTables());
			} else {
				for(ColumnRoutePair pair : partitionValue) {
					AbstractPartitionAlgorithm algorithm = tableConfig.getRule().getRuleAlgorithm();
					if(pair.colValue != null) {
						Integer tableIndex = algorithm.calculate(pair.colValue);
						if(tableIndex == null) {
							String msg = "can't find any valid datanode :" + tableConfig.getName()
									+ " -> " + tableConfig.getPartitionColumn() + " -> " + pair.colValue;
							LOGGER.warn(msg);
							throw new SQLNonTransientException(msg);
						}
						String subTable = tableConfig.getDistTables().get(tableIndex);
						if(subTable != null) {
							tablesRouteSet.add(subTable);
							if(algorithm instanceof SlotFunction){
								rrs.getDataNodeSlotMap().put(subTable,((SlotFunction) algorithm).slotValue());
							}
						}
					}
					if(pair.rangeValue != null) {
						Integer[] tableIndexs = algorithm
								.calculateRange(pair.rangeValue.beginValue.toString(), pair.rangeValue.endValue.toString());
						for(Integer idx : tableIndexs) {
							String subTable = tableConfig.getDistTables().get(idx);
							if(subTable != null) {
								tablesRouteSet.add(subTable);
								if(algorithm instanceof SlotFunction){
									rrs.getDataNodeSlotMap().put(subTable,((SlotFunction) algorithm).slotValue());
								}
							}
						}
					}
				}
			}
		}

		Object[] subTables =  tablesRouteSet.toArray();
		RouteResultsetNode[] nodes = new RouteResultsetNode[subTables.length];
	   Map<String,Integer> dataNodeSlotMap=	rrs.getDataNodeSlotMap();
		for(int i=0;i<nodes.length;i++){
			String table = String.valueOf(subTables[i]);
			String changeSql = orgSql;
			nodes[i] = new RouteResultsetNode(dataNode, rrs.getSqlType(), changeSql);//rrs.getStatement()
			nodes[i].setSubTableName(table);
			nodes[i].setSource(rrs);
			if(rrs.getDataNodeSlotMap().containsKey(dataNode)){
				nodes[i].setSlot(rrs.getDataNodeSlotMap().get(dataNode));
			}
			if (rrs.getCanRunInReadDB() != null) {
				nodes[i].setCanRunInReadDB(rrs.getCanRunInReadDB());
			}
			if(dataNodeSlotMap.containsKey(table))  {
				nodes[i].setSlot(dataNodeSlotMap.get(table));
			}
			if(rrs.getRunOnSlave() != null){
				nodes[0].setRunOnSlave(rrs.getRunOnSlave());
			}
		}
		rrs.setNodes(nodes);
		rrs.setSubTables(tablesRouteSet);
		rrs.setFinishedRoute(true);

		return rrs;
	}

	/**
	 * ?????????????????????
	 */
	public static void findRouteWithcConditionsForTables(SchemaConfig schema, RouteResultset rrs,
			Map<String, Map<String, Set<ColumnRoutePair>>> tablesAndConditions,
			Map<String, Set<String>> tablesRouteMap, String sql, LayerCachePool cachePool, boolean isSelect)
			throws SQLNonTransientException {

		//?????????????????????
		for(Map.Entry<String, Map<String, Set<ColumnRoutePair>>> entry : tablesAndConditions.entrySet()) {
			String tableName = entry.getKey().toUpperCase();
			TableConfig tableConfig = schema.getTables().get(tableName);
			if(tableConfig == null) {
				String msg = "can't find table define in schema "
						+ tableName + " schema:" + schema.getName();
				LOGGER.warn(msg);
				throw new SQLNonTransientException(msg);
			}
			if(tableConfig.getDistTables()!=null && tableConfig.getDistTables().size()>0){
				routeToDistTableNode(tableName,schema,rrs,sql, tablesAndConditions, cachePool,isSelect);
			}
			//??????????????????????????????????????????????????????????????????
			if(tableConfig.isGlobalTable() || schema.getTables().get(tableName).getDataNodes().size() == 1) {
				continue;
			} else {//???????????????????????????childTable?????????
				Map<String, Set<ColumnRoutePair>> columnsMap = entry.getValue();
				String joinKey = tableConfig.getJoinKey();
				String partionCol = tableConfig.getPartitionColumn();
				String primaryKey = tableConfig.getPrimaryKey();
				boolean isFoundPartitionValue = partionCol != null && entry.getValue().get(partionCol) != null;
                boolean isLoadData=false;
                if (LOGGER.isDebugEnabled()
						&& sql.startsWith(LoadData.loadDataHint)||rrs.isLoadData()) {
                     //??????load data????????????????????????????????????????????????????????????????????????load data?????????
                         isLoadData=true;
                }
				if(entry.getValue().get(primaryKey) != null && entry.getValue().size() == 1&&!isLoadData)
                {//????????????
					// try by primary key if found in cache
					Set<ColumnRoutePair> primaryKeyPairs = entry.getValue().get(primaryKey);
					if (primaryKeyPairs != null) {
						if (LOGGER.isDebugEnabled()) {
                                 LOGGER.debug("try to find cache by primary key ");
						}
						String tableKey = schema.getName() + '_' + tableName;
						boolean allFound = true;
						for (ColumnRoutePair pair : primaryKeyPairs) {//??????id in(1,2,3)?????????
							String cacheKey = pair.colValue;
							String dataNode = (String) cachePool.get(tableKey, cacheKey);
							if (dataNode == null) {
								allFound = false;
								continue;
							} else {
								if(tablesRouteMap.get(tableName) == null) {
									tablesRouteMap.put(tableName, new HashSet<String>());
								}
								tablesRouteMap.get(tableName).add(dataNode);
								continue;
							}
						}
						if (!allFound) {
							// need cache primary key ->datanode relation
							if (isSelect && tableConfig.getPrimaryKey() != null) {
								rrs.setPrimaryKey(tableKey + '.' + tableConfig.getPrimaryKey());
							}
						} else {//???????????????????????????????????????????????????
							continue;
						}
					}
				}
				if (isFoundPartitionValue) {//?????????
					Set<ColumnRoutePair> partitionValue = columnsMap.get(partionCol);
					if(partitionValue == null || partitionValue.size() == 0) {
						if(tablesRouteMap.get(tableName) == null) {
							tablesRouteMap.put(tableName, new HashSet<String>());
						}
						tablesRouteMap.get(tableName).addAll(tableConfig.getDataNodes());
					} else {
						for(ColumnRoutePair pair : partitionValue) {
							AbstractPartitionAlgorithm algorithm = tableConfig.getRule().getRuleAlgorithm();
							if(pair.colValue != null) {
								Integer nodeIndex = algorithm.calculate(pair.colValue);
								if(nodeIndex == null) {
									String msg = "can't find any valid datanode :" + tableConfig.getName()
											+ " -> " + tableConfig.getPartitionColumn() + " -> " + pair.colValue;
									LOGGER.warn(msg);
									throw new SQLNonTransientException(msg);
								}

								ArrayList<String> dataNodes = tableConfig.getDataNodes();
								String node;
								if (nodeIndex >=0 && nodeIndex < dataNodes.size()) {
									node = dataNodes.get(nodeIndex);

								} else {
									node = null;
									String msg = "Can't find a valid data node for specified node index :"
											+ tableConfig.getName() + " -> " + tableConfig.getPartitionColumn()
											+ " -> " + pair.colValue + " -> " + "Index : " + nodeIndex;
									LOGGER.warn(msg);
									throw new SQLNonTransientException(msg);
								}
								if(node != null) {
									if(tablesRouteMap.get(tableName) == null) {
										tablesRouteMap.put(tableName, new HashSet<String>());
									}
									if(algorithm instanceof SlotFunction){
										rrs.getDataNodeSlotMap().put(node,((SlotFunction) algorithm).slotValue());
									}
									tablesRouteMap.get(tableName).add(node);
								}
							}
							if(pair.rangeValue != null) {
								Integer[] nodeIndexs = algorithm
										.calculateRange(pair.rangeValue.beginValue.toString(), pair.rangeValue.endValue.toString());
								ArrayList<String> dataNodes = tableConfig.getDataNodes();
								String node;
								for(Integer idx : nodeIndexs) {
									if (idx >= 0 && idx < dataNodes.size()) {
										node = dataNodes.get(idx);
									} else {
										String msg = "Can't find valid data node(s) for some of specified node indexes :"
												+ tableConfig.getName() + " -> " + tableConfig.getPartitionColumn();
										LOGGER.warn(msg);
										throw new SQLNonTransientException(msg);
									}
									if(node != null) {
										if(tablesRouteMap.get(tableName) == null) {
											tablesRouteMap.put(tableName, new HashSet<String>());
										}
										if(algorithm instanceof SlotFunction){
											rrs.getDataNodeSlotMap().put(node,((SlotFunction) algorithm).slotValue());
										}
										tablesRouteMap.get(tableName).add(node);

									}
								}
							}
						}
					}
				} else if(joinKey != null && columnsMap.get(joinKey) != null && columnsMap.get(joinKey).size() != 0) {//childTable  (?????????select ??????????????????join)???????????????root table,???childTable??????,?????????root table
					Set<ColumnRoutePair> joinKeyValue = columnsMap.get(joinKey);

					Set<String> dataNodeSet = ruleByJoinValueCalculate(rrs, tableConfig, joinKeyValue);

					if (dataNodeSet.isEmpty()) {
						throw new SQLNonTransientException(
								"parent key can't find any valid datanode ");
					}
					if (LOGGER.isDebugEnabled()) {
						LOGGER.debug("found partion nodes (using parent partion rule directly) for child table to update  "
								+ Arrays.toString(dataNodeSet.toArray()) + " sql :" + sql);
					}
					if (dataNodeSet.size() > 1) {
						routeToMultiNode(rrs.isCacheAble(), rrs, dataNodeSet, sql);
						rrs.setFinishedRoute(true);
						return;
					} else {
						rrs.setCacheAble(true);
						routeToSingleNode(rrs, dataNodeSet.iterator().next(), sql);
						return;
					}

				} else {
					//??????????????????????????????????????????????????????
					if(tablesRouteMap.get(tableName) == null) {
						tablesRouteMap.put(tableName, new HashSet<String>());
					}
					boolean isSlotFunction= tableConfig.getRule() != null && tableConfig.getRule().getRuleAlgorithm() instanceof SlotFunction;
					if(isSlotFunction){
						for (String dn : tableConfig.getDataNodes()) {
							rrs.getDataNodeSlotMap().put(dn,-1);
						}
					}
					tablesRouteMap.get(tableName).addAll(tableConfig.getDataNodes());
				}
			}
		}
	}

	public static boolean isAllGlobalTable(DruidShardingParseInfo ctx, SchemaConfig schema) {
		boolean isAllGlobal = false;
		for(String table : ctx.getTables()) {
			TableConfig tableConfig = schema.getTables().get(table);
			if(tableConfig!=null && tableConfig.isGlobalTable()) {
				isAllGlobal = true;
			} else {
				return false;
			}
		}
		return isAllGlobal;
	}

	/**
	 *
	 * @param schema
	 * @param ctx
	 * @param tc
	 * @return true?????????????????????false?????????????????????
	 */
	public static boolean checkRuleRequired(SchemaConfig schema, DruidShardingParseInfo ctx, RouteCalculateUnit routeUnit, TableConfig tc) {
		if(!tc.isRuleRequired()) {
			return true;
		}
		boolean hasRequiredValue = false;
		String tableName = tc.getName();
		if(routeUnit.getTablesAndConditions().get(tableName) == null || routeUnit.getTablesAndConditions().get(tableName).size() == 0) {
			hasRequiredValue = false;
		} else {
			for(Map.Entry<String, Set<ColumnRoutePair>> condition : routeUnit.getTablesAndConditions().get(tableName).entrySet()) {

				String colName = condition.getKey();
				//???????????????????????????
				if(colName.equals(tc.getPartitionColumn())) {
					hasRequiredValue = true;
					break;
				}
			}
		}
		return hasRequiredValue;
	}


	/**
	 * ???????????????????????????????????????????????????dataNode
	 * @param schemaConfig
	 * @param tableName
	 * @return
	 */
	public static boolean isNoSharding(SchemaConfig schemaConfig, String tableName) {
		// Table???????????????????????????????????????schema
		tableName = tableName.toUpperCase();
		if (schemaConfig.isNoSharding()) {
			return true;
		}

		if (schemaConfig.getDataNode() != null && !schemaConfig.getTables().containsKey(tableName)) {
			return true;
		}

		return false;
	}

	/**
	 * ???????????????,??????sql????????????????????????????????????????????????
	 * @author lian
	 * @date 2016???12???2???
	 * @param tableName
	 * @return
	 */
	public static boolean isSystemSchema(String tableName) {
		// ???information_schema??? mysql?????????????????????
		if (tableName.startsWith("INFORMATION_SCHEMA.")
				|| tableName.startsWith("MYSQL.")
				|| tableName.startsWith("PERFORMANCE_SCHEMA.")) {
			return true;
		}

		return false;
	}

	/**
	 * ????????????????????????
	 * @param expr
	 * @return
	 */
	public static boolean isConditionAlwaysTrue(SQLExpr expr) {
		Object o = WallVisitorUtils.getValue(expr);
		if(Boolean.TRUE.equals(o)) {
			return true;
		}
		return false;
	}

	/**
	 * ???????????????????????????
	 * @param expr
	 * @return
	 */
	public static boolean isConditionAlwaysFalse(SQLExpr expr) {
		Object o = WallVisitorUtils.getValue(expr);
		if(Boolean.FALSE.equals(o)) {
			return true;
		}
		return false;
	}


	/**
	 * ???????????????????????????ER??????
	 * @param schema
	 * @param origSQL
	 * @param sc
	 * @return
	 * @throws SQLNonTransientException
	 * 
	 * ???????????????
	 *     edit by ding.w at 2017.4.28, ???????????? CLIENT_MULTI_STATEMENTS(insert into ; insert into)?????????
	 *     ???????????????mysql,???COM_QUERY?????????????????????insert?????????????????????er????????????????????????
	 *     
	 *     
	 */
	public static boolean processERChildTable(final SchemaConfig schema, final String origSQL,
            final ServerConnection sc) throws SQLNonTransientException {
	
		MySqlStatementParser parser = new MySqlStatementParser(origSQL);
		List<SQLStatement> statements = parser.parseStatementList();
		
		if(statements == null || statements.isEmpty() ) {
			throw new SQLNonTransientException(String.format("?????????SQL??????:%s", origSQL));
		}
		
		
		boolean erFlag = false; //?????????er???
		for(SQLStatement stmt : statements ) {
			MySqlInsertStatement insertStmt = (MySqlInsertStatement) stmt; 
			String tableName = insertStmt.getTableName().getSimpleName().toUpperCase();
			final TableConfig tc = schema.getTables().get(tableName);
			
			if (null != tc && tc.isChildTable()) {
				erFlag = true;
				
				String sql = insertStmt.toString();
				
				final RouteResultset rrs = new RouteResultset(sql, ServerParse.INSERT);
				String joinKey = tc.getJoinKey();
				//?????????Insert????????????MySqlInsertStatement??????parse
//				MySqlInsertStatement insertStmt = (MySqlInsertStatement) (new MySqlStatementParser(origSQL)).parseInsert();
				//??????????????????????????????????????????????????????joinkey??????index
				int joinKeyIndex = getJoinKeyIndex(insertStmt.getColumns(), joinKey);
				if (joinKeyIndex == -1) {
					String inf = "joinKey not provided :" + tc.getJoinKey() + "," + insertStmt;
					LOGGER.warn(inf);
					throw new SQLNonTransientException(inf);
				}
				//???????????????????????????
				if (isMultiInsert(insertStmt)) {
					String msg = "ChildTable multi insert not provided";
					LOGGER.warn(msg);
					throw new SQLNonTransientException(msg);
				}
				//??????joinkey??????
				String joinKeyVal = insertStmt.getValues().getValues().get(joinKeyIndex).toString();
				//??????bug #938???????????????????????????char????????????????????????"'"
				String realVal = joinKeyVal;
				if (joinKeyVal.startsWith("'") && joinKeyVal.endsWith("'") && joinKeyVal.length() > 2) {
					realVal = joinKeyVal.substring(1, joinKeyVal.length() - 1);
				}

				

				// try to route by ER parent partion key
				//????????????????????????????????????????????????,???????????????????????????joinkey???????????????routeByERParentKey
				RouteResultset theRrs = RouterUtil.routeByERParentKey(sc, schema, ServerParse.INSERT, sql, rrs, tc, realVal);
				if (theRrs != null) {
					boolean processedInsert=false;
					//?????????????????????????????????
	                if ( sc!=null && tc.isAutoIncrement()) {
	                    String primaryKey = tc.getPrimaryKey();
	                    processedInsert=processInsert(sc,schema,ServerParse.INSERT,sql,tc.getName(),primaryKey);
	                }
	                if(processedInsert==false){
	                	rrs.setFinishedRoute(true);
	                    sc.getSession2().execute(rrs, ServerParse.INSERT);
	                }
					// return true;
	                //?????????????????????
	                continue;
				}

				// route by sql query root parent's datanode
				//????????????????????????????????????????????????joinKey??????????????????????????????????????????????????????????????????datanode
				//??????????????????????????????parentkey???????????????????????????????????????
				final String findRootTBSql = tc.getLocateRTableKeySql().toLowerCase() + joinKeyVal;
				if (LOGGER.isDebugEnabled()) {
					LOGGER.debug("find root parent's node sql " + findRootTBSql);
				}

				ListenableFuture<String> listenableFuture = MycatServer.getInstance().
						getListeningExecutorService().submit(new Callable<String>() {
					@Override
					public String call() throws Exception {
						FetchStoreNodeOfChildTableHandler fetchHandler = new FetchStoreNodeOfChildTableHandler();
//						return fetchHandler.execute(schema.getName(), findRootTBSql, tc.getRootParent().getDataNodes());
						return fetchHandler.execute(schema.getName(), findRootTBSql, tc.getRootParent().getDataNodes(), sc);
					}
				});


				Futures.addCallback(listenableFuture, new FutureCallback<String>() {
					@Override
					public void onSuccess(String result) {
						//??????????????????????????????????????????????????????????????????
						if (Strings.isNullOrEmpty(result)) {
							StringBuilder s = new StringBuilder();
							LOGGER.warn(s.append(sc.getSession2()).append(origSQL).toString() +
									" err:" + "can't find (root) parent sharding node for sql:" + origSQL);
							if(!sc.isAutocommit()) { // ?????????????????????, ????????????
								sc.setTxInterrupt("can't find (root) parent sharding node for sql:" + origSQL);
							}
							sc.writeErrMessage(ErrorCode.ER_PARSE_ERROR, "can't find (root) parent sharding node for sql:" + origSQL);
							return;
						}

						if (LOGGER.isDebugEnabled()) {
							LOGGER.debug("found partion node for child table to insert " + result + " sql :" + origSQL);
						}
						//???????????????????????????????????????????????????????????????????????????????????????ID???
						boolean processedInsert=false;
	                    if ( sc!=null && tc.isAutoIncrement()) {
	                        try {
	                            String primaryKey = tc.getPrimaryKey();
								processedInsert=processInsert(sc,schema,ServerParse.INSERT,origSQL,tc.getName(),primaryKey);
							} catch (SQLNonTransientException e) {
								LOGGER.warn("sequence processInsert error,",e);
			                    sc.writeErrMessage(ErrorCode.ER_PARSE_ERROR , "sequence processInsert error," + e.getMessage());
							}
	                    }
	                    if(processedInsert==false){
	                    	RouteResultset executeRrs = RouterUtil.routeToSingleNode(rrs, result, origSQL);
	    					sc.getSession2().execute(executeRrs, ServerParse.INSERT);
	                    }

					}

					@Override
					public void onFailure(Throwable t) {
						StringBuilder s = new StringBuilder();
						LOGGER.warn(s.append(sc.getSession2()).append(origSQL).toString() +
								" err:" + t.getMessage());
						sc.writeErrMessage(ErrorCode.ER_PARSE_ERROR, t.getMessage() + " " + s.toString());
					}
				}, MycatServer.getInstance().
						getListeningExecutorService());
				
			} else if(erFlag) {
				throw new SQLNonTransientException(String.format("%s????????????ER????????????", origSQL));
			}
		}
		
		
		return erFlag;
	}

	/**
	 * ??????joinKey?????????
	 *
	 * @param columns
	 * @param joinKey
	 * @return -1??????????????????>=0???????????????
	 */
	private static int getJoinKeyIndex(List<SQLExpr> columns, String joinKey) {
		for (int i = 0; i < columns.size(); i++) {
			String col = StringUtil.removeBackquote(columns.get(i).toString()).toUpperCase();
			if (col.equals(joinKey)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * ????????????????????????insert into ...values (),()...??? insert into ...select.....
	 *
	 * @param insertStmt
	 * @return
	 */
	private static boolean isMultiInsert(MySqlInsertStatement insertStmt) {
		return (insertStmt.getValuesList() != null && insertStmt.getValuesList().size() > 1)
				|| insertStmt.getQuery() != null;
	}

}
