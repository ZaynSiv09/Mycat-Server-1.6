package io.mycat.route.impl;

import java.sql.SQLNonTransientException;
import java.sql.SQLSyntaxErrorException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLAllExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLInsertStatement;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectQuery;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlReplaceStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.stat.TableStat.Relationship;
import com.google.common.base.Strings;

import io.mycat.MycatServer;
import io.mycat.backend.mysql.nio.handler.MiddlerQueryResultHandler;
import io.mycat.backend.mysql.nio.handler.MiddlerResultHandler;
import io.mycat.backend.mysql.nio.handler.SecondHandler;
import io.mycat.cache.LayerCachePool;
import io.mycat.config.ErrorCode;
import io.mycat.config.model.SchemaConfig;
import io.mycat.config.model.TableConfig;
import io.mycat.config.model.rule.RuleConfig;
import io.mycat.route.RouteResultset;
import io.mycat.route.RouteResultsetNode;
import io.mycat.route.function.SlotFunction;
import io.mycat.route.impl.middlerResultStrategy.BinaryOpResultHandler;
import io.mycat.route.impl.middlerResultStrategy.InSubQueryResultHandler;
import io.mycat.route.impl.middlerResultStrategy.RouteMiddlerReaultHandler;
import io.mycat.route.impl.middlerResultStrategy.SQLAllResultHandler;
import io.mycat.route.impl.middlerResultStrategy.SQLExistsResultHandler;
import io.mycat.route.impl.middlerResultStrategy.SQLQueryResultHandler;
import io.mycat.route.parser.druid.DruidParser;
import io.mycat.route.parser.druid.DruidParserFactory;
import io.mycat.route.parser.druid.DruidShardingParseInfo;
import io.mycat.route.parser.druid.MycatSchemaStatVisitor;
import io.mycat.route.parser.druid.MycatStatementParser;
import io.mycat.route.parser.druid.RouteCalculateUnit;
import io.mycat.route.parser.util.ParseUtil;
import io.mycat.route.util.RouterUtil;
import io.mycat.server.NonBlockingSession;
import io.mycat.server.ServerConnection;
import io.mycat.server.parser.ServerParse;

public class DruidMycatRouteStrategy extends AbstractRouteStrategy {
	
	public static final Logger LOGGER = LoggerFactory.getLogger(DruidMycatRouteStrategy.class);
	
	private static Map<Class<?>,RouteMiddlerReaultHandler> middlerResultHandler = new HashMap<>();
	
	static{
		middlerResultHandler.put(SQLQueryExpr.class, new SQLQueryResultHandler());
		middlerResultHandler.put(SQLBinaryOpExpr.class, new BinaryOpResultHandler());
		middlerResultHandler.put(SQLInSubQueryExpr.class, new InSubQueryResultHandler());
		middlerResultHandler.put(SQLExistsExpr.class, new SQLExistsResultHandler());
		middlerResultHandler.put(SQLAllExpr.class, new SQLAllResultHandler());
	}
	
	
	@Override
	public RouteResultset routeNormalSqlWithAST(SchemaConfig schema,
			String stmt, RouteResultset rrs,String charset,
			LayerCachePool cachePool,int sqlType,ServerConnection sc) throws SQLNonTransientException {
		
		/**
		 *  ??????mysql????????????mysql??????
		 */
		SQLStatementParser parser = null;
		if (schema.isNeedSupportMultiDBType()) {
			parser = new MycatStatementParser(stmt);
		} else {
			parser = new MySqlStatementParser(stmt); 
		}

		MycatSchemaStatVisitor visitor = null;
		SQLStatement statement;
		
		/**
		 * ???????????????????????????SQL????????????
		 */
		try {
			statement = parser.parseStatement();
            visitor = new MycatSchemaStatVisitor();
		} catch (Exception t) {
	        LOGGER.error("DruidMycatRouteStrategyError", t);
			throw new SQLSyntaxErrorException(t);
		}

		/**
		 * ??????unsupported statement
		 */
		checkUnSupportedStatement(statement);

		DruidParser druidParser = DruidParserFactory.create(schema, statement, visitor);
		druidParser.parser(schema, rrs, statement, stmt,cachePool,visitor);
		DruidShardingParseInfo ctx=  druidParser.getCtx() ;
		rrs.setTables(ctx.getTables());
		
		if(visitor.isSubqueryRelationOr()){
			String err = "In subQuery,the or condition is not supported.";
			LOGGER.error(err);
			throw new SQLSyntaxErrorException(err);
		}
		
		/* ????????????????????????
			1.2.1 ??????????????????.
       		1.2.2 ???????????????join???sql.??????calat
       		1.2.3 ???????????????subquery ???sql.???subquery????????????.???????????????,???outerquery
		 */
		
		//add huangyiming ?????????????????????????????????????????????????????????Catlet
		List<String> tables = ctx.getTables();
		SchemaConfig schemaConf = MycatServer.getInstance().getConfig().getSchemas().get(schema.getName());
		int index = 0;
		RuleConfig firstRule = null;
		boolean directRoute = true;
		Set<String> firstDataNodes = new HashSet<String>();
		Map<String, TableConfig> tconfigs = schemaConf==null?null:schemaConf.getTables();
		
		Map<String,RuleConfig> rulemap = new HashMap<>();
		if(tconfigs!=null){	
	        for(String tableName : tables){
	            TableConfig tc =  tconfigs.get(tableName);
	            if(tc == null){
	              //add ????????????
	              Map<String, String> tableAliasMap = ctx.getTableAliasMap();
	              if(tableAliasMap !=null && tableAliasMap.get(tableName) !=null){
	                tc = schemaConf.getTables().get(tableAliasMap.get(tableName));
	              }
	            }

	            if(index == 0){
	            	 if(tc !=null){
		                firstRule=  tc.getRule();
						//???????????????????????????,????????????
		                if(firstRule==null){
		                	continue;
		                }
		                firstDataNodes.addAll(tc.getDataNodes());
		                rulemap.put(tc.getName(), firstRule);
	            	 }
	            }else{
	                if(tc !=null){
	                  //ER????????????????????????????????????????????????tablerule?????????,??????????????????
	                    RuleConfig ruleCfg = tc.getRule();
	                    if(ruleCfg==null){  //???????????????????????????,????????????
	                    	continue;
	                    }
	                    Set<String> dataNodes = new HashSet<String>();
	                    dataNodes.addAll(tc.getDataNodes());
	                    rulemap.put(tc.getName(), ruleCfg);
	                    //??????????????????????????????????????????datanode????????????????????????????????????
	                    if(firstRule!=null&&((ruleCfg !=null && !ruleCfg.getRuleAlgorithm().equals(firstRule.getRuleAlgorithm()) )||( !dataNodes.equals(firstDataNodes)))){
	                      directRoute = false;
	                      break;
	                    }
	                }
	            }
	            index++;
	        }
		} 
		
		RouteResultset rrsResult = rrs;
		if(directRoute){ //????????????
			if(!RouterUtil.isAllGlobalTable(ctx, schemaConf)){
				if(rulemap.size()>1&&!checkRuleField(rulemap,visitor)){
					String err = "In case of slice table,sql have same rules,but the relationship condition is different from rule field!";
					LOGGER.error(err);
					throw new SQLSyntaxErrorException(err);
				}
			}
			rrsResult = directRoute(rrs,ctx,schema,druidParser,statement,cachePool);
		}else{
			int subQuerySize = visitor.getSubQuerys().size();
			if(subQuerySize==0&&ctx.getTables().size()==2){ //????????????,????????????catlet
			    if(!visitor.getRelationships().isEmpty()){
			    	rrs.setCacheAble(false);
			    	rrs.setFinishedRoute(true);
			    	rrsResult = catletRoute(schema,ctx.getSql(),charset,sc);
				}else{
					rrsResult = directRoute(rrs,ctx,schema,druidParser,statement,cachePool);
				}
			}else if(subQuerySize==1){     //??????????????????????????????,??????  MiddlerResultHandler ?????????????????????,???????????? sql ???????????? TODO ?????????????????????????????????????????????.
				SQLSelect sqlselect = visitor.getSubQuerys().iterator().next();
				if(!visitor.getRelationships().isEmpty()){     // ??? inner query  ??? outer  query  ??????????????????,????????????
					String err = "In case of slice table,sql have different rules,the relationship condition is not supported.";
					LOGGER.error(err);
					throw new SQLSyntaxErrorException(err);
				}else{
					SQLSelectQuery sqlSelectQuery = sqlselect.getQuery();
					if(((MySqlSelectQueryBlock)sqlSelectQuery).getFrom() instanceof SQLExprTableSource) {
						rrs.setCacheAble(false);
						rrs.setFinishedRoute(true);
						rrsResult = middlerResultRoute(schema,charset,sqlselect,sqlType,statement,sc);
					}
				}
			}else if(subQuerySize >=2){
				String err = "In case of slice table,sql has different rules,currently only one subQuery is supported.";
				LOGGER.error(err);
				throw new SQLSyntaxErrorException(err);
			}
		}
		return rrsResult;
	}
	
	/**
	 * ??????????????????????????????????????????,???????????????????????????????????????
	 * @param rulemap
	 * @param ships
	 * @return
	 */
	private boolean checkRuleField(Map<String,RuleConfig> rulemap,MycatSchemaStatVisitor visitor){
		
		Set<Relationship> ships = visitor.getRelationships();
		Iterator<Relationship> iter = ships.iterator();
		while(iter.hasNext()){
			Relationship ship = iter.next();
			String lefttable = ship.getLeft().getTable().toUpperCase();
			String righttable = ship.getRight().getTable().toUpperCase();
			// ???????????????????????????????????????,????????????
			if(lefttable.equals(righttable)){
				return true;
			}
			RuleConfig leftconfig = rulemap.get(lefttable);
			if(leftconfig!=null){
				if(!leftconfig.getColumn().equals(ship.getLeft().getName().toUpperCase())){
					return false;
				}
			}
			RuleConfig rightconfig = rulemap.get(righttable);
			if(rightconfig!=null){
				if(!rightconfig.getColumn().equals(ship.getRight().getName().toUpperCase())){
					return false;
				}
			}
		}
		return true;
	}
	
	private RouteResultset middlerResultRoute(final SchemaConfig schema,final String charset,final SQLSelect sqlselect,
												final int sqlType,final SQLStatement statement,final ServerConnection sc){
		
		final String middlesql = SQLUtils.toMySqlString(sqlselect);
		
    	MiddlerResultHandler<String> middlerResultHandler =  new MiddlerQueryResultHandler<>(new SecondHandler() {						 
				@Override
				public void doExecute(List param) {
					sc.getSession2().setMiddlerResultHandler(null);
					String sqls = null;
					// ????????????
					RouteResultset rrs = null;
					try {
						
						sqls = buildSql(statement,sqlselect,param);
						rrs = MycatServer
								.getInstance()
								.getRouterservice()
								.route(MycatServer.getInstance().getConfig().getSystem(),
										schema, sqlType,sqls.toLowerCase(), charset,sc );

					} catch (Exception e) {
						StringBuilder s = new StringBuilder();
						LOGGER.warn(s.append(this).append(sqls).toString() + " err:" + e.toString(),e);
						String msg = e.getMessage();
						sc.writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
						return;
					}
					NonBlockingSession noBlockSession =  new NonBlockingSession(sc.getSession2().getSource());
					noBlockSession.setMiddlerResultHandler(null);
					//session????????????????????????
					noBlockSession.setPrepared(sc.getSession2().isPrepared());
					if (rrs != null) {						
						noBlockSession.setCanClose(false);
						noBlockSession.execute(rrs, ServerParse.SELECT);
					}
				}
			} );
    	sc.getSession2().setMiddlerResultHandler(middlerResultHandler);
    	sc.getSession2().setCanClose(false);
    
		// ????????????
		RouteResultset rrs = null;
		try {
			rrs = MycatServer
					.getInstance()
					.getRouterservice()
					.route(MycatServer.getInstance().getConfig().getSystem(),
							schema, ServerParse.SELECT, middlesql, charset, sc);
	
		} catch (Exception e) {
			StringBuilder s = new StringBuilder();
			LOGGER.warn(s.append(this).append(middlesql).toString() + " err:" + e.toString(),e);
			String msg = e.getMessage();
			sc.writeErrMessage(ErrorCode.ER_PARSE_ERROR, msg == null ? e.getClass().getSimpleName() : msg);
			return null;
		}
		
		if(rrs!=null){
			rrs.setCacheAble(false);
		}
		return rrs;
	}
	
	/**
	 * ??????????????????????????????,????????????sql ????????????.
	 * @param statement
	 * @param sqlselect
	 * @param param
	 * @return
	 */
	private String buildSql(SQLStatement statement,SQLSelect sqlselect,List param){

		SQLObject parent = sqlselect.getParent();
		RouteMiddlerReaultHandler handler = middlerResultHandler.get(parent.getClass());
		if(handler==null){
			throw new UnsupportedOperationException(parent.getClass()+" current is not supported ");
		}
		return handler.dohandler(statement, sqlselect, parent, param);
	}
	
	/**
	 * ?????????????????????catlet
	 * @param schema
	 * @param stmt
	 * @param charset
	 * @param sc
	 * @return
	 */
	private RouteResultset catletRoute(SchemaConfig schema,String stmt,String charset,ServerConnection sc){
		RouteResultset rrs = null;
		try {
			rrs = MycatServer
					.getInstance()
					.getRouterservice()
					.route(MycatServer.getInstance().getConfig().getSystem(),
							schema, ServerParse.SELECT, "/*!mycat:catlet=io.mycat.catlets.ShareJoin */ "+stmt, charset, sc);
			
		}catch(Exception e){
			
		}
		return rrs;
	}
	
	/**
	 *  ??????????????????
	 * @param rrs
	 * @param ctx
	 * @param schema
	 * @param druidParser
	 * @param statement
	 * @param cachePool
	 * @return
	 * @throws SQLNonTransientException
	 */
	private RouteResultset directRoute(RouteResultset rrs,DruidShardingParseInfo ctx,SchemaConfig schema,
										DruidParser druidParser,SQLStatement statement,LayerCachePool cachePool) throws SQLNonTransientException{
		
		//??????sql??????insert?????????????????????, ?????????????????????????????????,??????sql ????????????
		druidParser.changeSql(schema, rrs, statement,cachePool);
		
		/**
		 * DruidParser ????????????????????????????????????????????????
		 */
		if ( rrs.isFinishedRoute() ) {
			return rrs;
		}
		
		/**
		 * ??????from???select???????????????
		 */
        if((ctx.getTables() == null || ctx.getTables().size() == 0)&&(ctx.getTableAliasMap()==null||ctx.getTableAliasMap().isEmpty()))
        {
		    return RouterUtil.routeToSingleNode(rrs, schema.getRandomDataNode(), druidParser.getCtx().getSql());
		}

		if(druidParser.getCtx().getRouteCalculateUnits().size() == 0) {
			RouteCalculateUnit routeCalculateUnit = new RouteCalculateUnit();
			druidParser.getCtx().addRouteCalculateUnit(routeCalculateUnit);
		}
		
		SortedSet<RouteResultsetNode> nodeSet = new TreeSet<RouteResultsetNode>();
		boolean isAllGlobalTable = RouterUtil.isAllGlobalTable(ctx, schema);
		for(RouteCalculateUnit unit: druidParser.getCtx().getRouteCalculateUnits()) {
			RouteResultset rrsTmp = RouterUtil.tryRouteForTables(schema, druidParser.getCtx(), unit, rrs, isSelect(statement), cachePool);
			if(rrsTmp != null&&rrsTmp.getNodes()!=null) {
				for(RouteResultsetNode node :rrsTmp.getNodes()) {
					nodeSet.add(node);
				}
			}
			if(isAllGlobalTable) {//???????????????????????????????????????
				break;
			}
		}
		
		RouteResultsetNode[] nodes = new RouteResultsetNode[nodeSet.size()];
		int i = 0;
		for (RouteResultsetNode aNodeSet : nodeSet) {
			nodes[i] = aNodeSet;
			  if(statement instanceof MySqlInsertStatement &&ctx.getTables().size()==1&&schema.getTables().containsKey(ctx.getTables().get(0))) {
				  RuleConfig rule = schema.getTables().get(ctx.getTables().get(0)).getRule();
				  if(rule!=null&&  rule.getRuleAlgorithm() instanceof SlotFunction){
					 aNodeSet.setStatement(ParseUtil.changeInsertAddSlot(aNodeSet.getStatement(),aNodeSet.getSlot()));
				  }
			  }
			i++;
		}		
		rrs.setNodes(nodes);		
		
		//??????
		/**
		 *  subTables="t_order$1-2,t_order3"
		 *???????????? 1.6 ???????????? ?????? dataNode ???????????????????????????????????????????????????????????????join???
		 */
		if(rrs.isDistTable()){
			return this.routeDisTable(statement,rrs);
		}
		return rrs;
	}
	
	private SQLExprTableSource getDisTable(SQLTableSource tableSource,RouteResultsetNode node) throws SQLSyntaxErrorException{
		if(node.getSubTableName()==null){
			String msg = " sub table not exists for " + node.getName() + " on " + tableSource;
			LOGGER.error("DruidMycatRouteStrategyError " + msg);
			throw new SQLSyntaxErrorException(msg);
		}
		
		SQLIdentifierExpr sqlIdentifierExpr = new SQLIdentifierExpr();
		sqlIdentifierExpr.setParent(tableSource.getParent());
		sqlIdentifierExpr.setName(node.getSubTableName());
		SQLExprTableSource from2 = new SQLExprTableSource(sqlIdentifierExpr);
		return from2;
	}
	
	private RouteResultset routeDisTable(SQLStatement statement, RouteResultset rrs) throws SQLSyntaxErrorException{
		SQLTableSource tableSource = null;
		if(statement instanceof SQLInsertStatement) {
			SQLInsertStatement insertStatement = (SQLInsertStatement) statement;
			tableSource = insertStatement.getTableSource();
			for (RouteResultsetNode node : rrs.getNodes()) {
				SQLExprTableSource from2 = getDisTable(tableSource, node);
				insertStatement.setTableSource(from2);
				node.setStatement(insertStatement.toString());
	        }
		}
		if(statement instanceof SQLDeleteStatement) {
			SQLDeleteStatement deleteStatement = (SQLDeleteStatement) statement;
			tableSource = deleteStatement.getTableSource();
			for (RouteResultsetNode node : rrs.getNodes()) {
				SQLExprTableSource from2 = getDisTable(tableSource, node);
				deleteStatement.setTableSource(from2);
				node.setStatement(deleteStatement.toString());
	        }
		}
		if(statement instanceof SQLUpdateStatement) {
			SQLUpdateStatement updateStatement = (SQLUpdateStatement) statement;
			tableSource = updateStatement.getTableSource();
			for (RouteResultsetNode node : rrs.getNodes()) {
				SQLExprTableSource from2 = getDisTable(tableSource, node);
				updateStatement.setTableSource(from2);
				node.setStatement(updateStatement.toString());
	        }
		}
		
		return rrs;
	}

	/**
	 * SELECT ??????
	 */
    private boolean isSelect(SQLStatement statement) {
		if(statement instanceof SQLSelectStatement) {
			return true;
		}
		return false;
	}
	
	/**
	 * ??????????????????SQLStatement?????? ??????????????????????????????SQLSyntaxErrorException??????
	 * @param statement
	 * @throws SQLSyntaxErrorException
	 */
	private void checkUnSupportedStatement(SQLStatement statement) throws SQLSyntaxErrorException {
		//?????????replace??????
		if(statement instanceof MySqlReplaceStatement) {
			throw new SQLSyntaxErrorException(" ReplaceStatement can't be supported,use insert into ...on duplicate key update... instead ");
		}
	}
	
	/**
	 * ?????? SHOW SQL
	 */
	@Override
	public RouteResultset analyseShowSQL(SchemaConfig schema,
			RouteResultset rrs, String stmt) throws SQLSyntaxErrorException {
		
		String upStmt = stmt.toUpperCase();
		int tabInd = upStmt.indexOf(" TABLES");
		if (tabInd > 0) {// show tables
			int[] nextPost = RouterUtil.getSpecPos(upStmt, 0);
			if (nextPost[0] > 0) {// remove db info
				int end = RouterUtil.getSpecEndPos(upStmt, tabInd);
				if (upStmt.indexOf(" FULL") > 0) {
					stmt = "SHOW FULL TABLES" + stmt.substring(end);
				} else {
					stmt = "SHOW TABLES" + stmt.substring(end);
				}
			}
          String defaultNode=  schema.getDataNode();
            if(!Strings.isNullOrEmpty(defaultNode))
            {
             return    RouterUtil.routeToSingleNode(rrs, defaultNode, stmt);
            }
			return RouterUtil.routeToMultiNode(false, rrs, schema.getMetaDataNodes(), stmt);
		}
		
		/**
		 *  show index or column
		 */
		int[] indx = RouterUtil.getSpecPos(upStmt, 0);
		if (indx[0] > 0) {
			/**
			 *  has table
			 */
			int[] repPos = { indx[0] + indx[1], 0 };
			String tableName = RouterUtil.getShowTableName(stmt, repPos);
			/**
			 *  IN DB pattern
			 */
			int[] indx2 = RouterUtil.getSpecPos(upStmt, indx[0] + indx[1] + 1);
			if (indx2[0] > 0) {// find LIKE OR WHERE
				repPos[1] = RouterUtil.getSpecEndPos(upStmt, indx2[0] + indx2[1]);

			}
			stmt = stmt.substring(0, indx[0]) + " FROM " + tableName + stmt.substring(repPos[1]);
			RouterUtil.routeForTableMeta(rrs, schema, tableName, stmt);
			return rrs;

		}
		
		/**
		 *  show create table tableName
		 */
		int[] createTabInd = RouterUtil.getCreateTablePos(upStmt, 0);
		if (createTabInd[0] > 0) {
			int tableNameIndex = createTabInd[0] + createTabInd[1];
			if (upStmt.length() > tableNameIndex) {
				String tableName = stmt.substring(tableNameIndex).trim();
				int ind2 = tableName.indexOf('.');
				if (ind2 > 0) {
					tableName = tableName.substring(ind2 + 1);
				}
				RouterUtil.routeForTableMeta(rrs, schema, tableName, stmt);
				return rrs;
			}
		}

		return RouterUtil.routeToSingleNode(rrs, schema.getRandomDataNode(), stmt);
	}
	
	
//	/**
//	 * ??????????????????????????????
//	 * @param schema
//	 * @param tablesAndConditions
//	 * @param tablesRouteMap
//	 * @throws SQLNonTransientException
//	 */
//	private static RouteResultset findRouteWithcConditionsForOneTable(SchemaConfig schema, RouteResultset rrs,
//		Map<String, Set<ColumnRoutePair>> conditions, String tableName, String sql) throws SQLNonTransientException {
//		boolean cache = rrs.isCacheAble();
//	    //?????????????????????
//		tableName = tableName.toUpperCase();
//		TableConfig tableConfig = schema.getTables().get(tableName);
//		//??????????????????????????????????????????????????????????????????
//		if(tableConfig.isGlobalTable()) {
//			return null;
//		} else {//????????????
//			Set<String> routeSet = new HashSet<String>();
//			String joinKey = tableConfig.getJoinKey();
//			for(Map.Entry<String, Set<ColumnRoutePair>> condition : conditions.entrySet()) {
//				String colName = condition.getKey();
//				//???????????????????????????
//				if(colName.equals(tableConfig.getPartitionColumn())) {
//					Set<ColumnRoutePair> columnPairs = condition.getValue();
//					
//					for(ColumnRoutePair pair : columnPairs) {
//						if(pair.colValue != null) {
//							Integer nodeIndex = tableConfig.getRule().getRuleAlgorithm().calculate(pair.colValue);
//							if(nodeIndex == null) {
//								String msg = "can't find any valid datanode :" + tableConfig.getName() 
//										+ " -> " + tableConfig.getPartitionColumn() + " -> " + pair.colValue;
//								LOGGER.warn(msg);
//								throw new SQLNonTransientException(msg);
//							}
//							String node = tableConfig.getDataNodes().get(nodeIndex);
//							if(node != null) {//????????????????????????
//								routeSet.add(node);
//							}
//						}
//						if(pair.rangeValue != null) {
//							Integer[] nodeIndexs = tableConfig.getRule().getRuleAlgorithm()
//									.calculateRange(pair.rangeValue.beginValue.toString(), pair.rangeValue.endValue.toString());
//							for(Integer idx : nodeIndexs) {
//								String node = tableConfig.getDataNodes().get(idx);
//								if(node != null) {//????????????????????????
//									routeSet.add(node);
//								}
//							}
//						}
//					}
//				} else if(joinKey != null && joinKey.equals(colName)) {
//					Set<String> dataNodeSet = RouterUtil.ruleCalculate(
//							tableConfig.getParentTC(), condition.getValue());
//					if (dataNodeSet.isEmpty()) {
//						throw new SQLNonTransientException(
//								"parent key can't find any valid datanode ");
//					}
//					if (LOGGER.isDebugEnabled()) {
//						LOGGER.debug("found partion nodes (using parent partion rule directly) for child table to update  "
//								+ Arrays.toString(dataNodeSet.toArray()) + " sql :" + sql);
//					}
//					if (dataNodeSet.size() > 1) {
//						return RouterUtil.routeToMultiNode(rrs.isCacheAble(), rrs, schema.getAllDataNodes(), sql);
//					} else {
//						rrs.setCacheAble(true);
//						return RouterUtil.routeToSingleNode(rrs, dataNodeSet.iterator().next(), sql);
//					}
//				} else {//???????????????????????????????????????join??????,??????
//					continue;
//					
//				}
//			}
//			return RouterUtil.routeToMultiNode(cache, rrs, routeSet, sql);
//			
//		}
//
//	}

	public RouteResultset routeSystemInfo(SchemaConfig schema, int sqlType,
			String stmt, RouteResultset rrs) throws SQLSyntaxErrorException {
		switch(sqlType){
		case ServerParse.SHOW:// if origSQL is like show tables
			return analyseShowSQL(schema, rrs, stmt);
		case ServerParse.SELECT://if origSQL is like select @@
			int index = stmt.indexOf("@@");
			if(index > 0 && "SELECT".equals(stmt.substring(0, index).trim().toUpperCase())){
				return analyseDoubleAtSgin(schema, rrs, stmt);
			}
			break;
		case ServerParse.DESCRIBE:// if origSQL is meta SQL, such as describe table
			int ind = stmt.indexOf(' ');
			stmt = stmt.trim();
			return analyseDescrSQL(schema, rrs, stmt, ind + 1);
		}
		return null;
	}
	
	/**
	 * ???Desc?????????????????? ????????????????????????
	 * 	 * 
	 * @param schema   				????????????
	 * @param rrs    				??????????????????
	 * @param stmt   				????????????
	 * @param ind    				?????????' '?????????
	 * @return RouteResultset		(??????????????????)
	 * @author mycat
	 */
	private static RouteResultset analyseDescrSQL(SchemaConfig schema,
			RouteResultset rrs, String stmt, int ind) {
		
		final String MATCHED_FEATURE = "DESCRIBE ";
		final String MATCHED2_FEATURE = "DESC ";
		int pos = 0;
		while (pos < stmt.length()) {
			char ch = stmt.charAt(pos);
			// ?????????????????? /* */ BEN
			if(ch == '/' &&  pos+4 < stmt.length() && stmt.charAt(pos+1) == '*') {
				if(stmt.substring(pos+2).indexOf("*/") != -1) {
					pos += stmt.substring(pos+2).indexOf("*/")+4;
					continue;
				} else {
					// ??????????????????????????????
					throw new IllegalArgumentException("sql ?????? ????????????");
				}
			} else if(ch == 'D'||ch == 'd') {
				// ?????? [describe ] 
				if(pos+MATCHED_FEATURE.length() < stmt.length() && (stmt.substring(pos).toUpperCase().indexOf(MATCHED_FEATURE) != -1)) {
					pos = pos + MATCHED_FEATURE.length();
					break;
				} else if(pos+MATCHED2_FEATURE.length() < stmt.length() && (stmt.substring(pos).toUpperCase().indexOf(MATCHED2_FEATURE) != -1)) {
					pos = pos + MATCHED2_FEATURE.length();
					break;
				} else {
					pos++;
				}
			}
		}
		
		// ??????ind?????????BEN GONG
		ind = pos;		
		int[] repPos = { ind, 0 };
		String tableName = RouterUtil.getTableName(stmt, repPos);
		
		stmt = stmt.substring(0, ind) + tableName + stmt.substring(repPos[1]);
		RouterUtil.routeForTableMeta(rrs, schema, tableName, stmt);
		return rrs;
	}
	
	/**
	 * ????????????????????????????????????
	 * 
	 * @param schema     			????????????
	 * @param rrs		  		 	??????????????????
	 * @param stmt		  	 		??????sql
	 * @return RouteResultset		??????????????????
	 * @throws SQLSyntaxErrorException
	 * @author mycat
	 */
	private RouteResultset analyseDoubleAtSgin(SchemaConfig schema,
			RouteResultset rrs, String stmt) throws SQLSyntaxErrorException {		
		String upStmt = stmt.toUpperCase();
		int atSginInd = upStmt.indexOf(" @@");
		if (atSginInd > 0) {
			return RouterUtil.routeToMultiNode(false, rrs, schema.getMetaDataNodes(), stmt);
		}
		return RouterUtil.routeToSingleNode(rrs, schema.getRandomDataNode(), stmt);
	}
}