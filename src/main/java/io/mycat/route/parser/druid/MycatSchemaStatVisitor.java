package io.mycat.route.parser.druid;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.alibaba.druid.sql.ast.SQLCommentHint;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLExprImpl;
import com.alibaba.druid.sql.ast.SQLName;
import com.alibaba.druid.sql.ast.SQLObject;
import com.alibaba.druid.sql.ast.expr.SQLAggregateExpr;
import com.alibaba.druid.sql.ast.expr.SQLAllExpr;
import com.alibaba.druid.sql.ast.expr.SQLAnyExpr;
import com.alibaba.druid.sql.ast.expr.SQLBetweenExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLCharExpr;
import com.alibaba.druid.sql.ast.expr.SQLExistsExpr;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLInListExpr;
import com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.expr.SQLQueryExpr;
import com.alibaba.druid.sql.ast.expr.SQLSomeExpr;
import com.alibaba.druid.sql.ast.expr.SQLValuableExpr;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableItem;
import com.alibaba.druid.sql.ast.statement.SQLAlterTableStatement;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectItem;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlCreateTableStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlDeleteStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlHintStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlInsertStatement;
import com.alibaba.druid.sql.dialect.mysql.ast.statement.MySqlSelectQueryBlock;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlSchemaStatVisitor;
import com.alibaba.druid.sql.visitor.SchemaStatVisitor;
import com.alibaba.druid.stat.TableStat;
import com.alibaba.druid.stat.TableStat.Column;
import com.alibaba.druid.stat.TableStat.Condition;
import com.alibaba.druid.stat.TableStat.Mode;
import com.alibaba.druid.stat.TableStat.Relationship;

import io.mycat.route.util.RouterUtil;

/**
 * Druid?????????????????????ast?????????????????????????????????????????????vistor
 * @author wang.dw
 *
 */
public class MycatSchemaStatVisitor extends MySqlSchemaStatVisitor {
	private boolean hasOrCondition = false;
	private List<WhereUnit> whereUnits = new CopyOnWriteArrayList<WhereUnit>();
	private List<WhereUnit> storedwhereUnits = new CopyOnWriteArrayList<WhereUnit>();
	private List<SQLSelect> subQuerys = new CopyOnWriteArrayList<>();  //???????????????
	private boolean hasChange = false; // ???????????????sql
	private boolean subqueryRelationOr = false;   //??????????????????????????????????????????????????? or ??????
	
	private void reset() {
		this.conditions.clear();
		this.whereUnits.clear();
		this.hasOrCondition = false;
	}
	
	public List<WhereUnit> getWhereUnits() {
		return whereUnits;
	}

	public boolean hasOrCondition() {
		return hasOrCondition;
	}
	
    @Override
    public boolean visit(SQLSelectStatement x) {
        setAliasMap();
//        getAliasMap().put("DUAL", null);

        return true;
    }

    @Override
    public boolean visit(SQLBetweenExpr x) {
        String begin = null;
        if(x.beginExpr instanceof SQLCharExpr)
        {
            begin= (String) ( (SQLCharExpr)x.beginExpr).getValue();
        }  else {
            begin = x.beginExpr.toString();
        }
        String end = null;
        if(x.endExpr instanceof SQLCharExpr)
        {
            end= (String) ( (SQLCharExpr)x.endExpr).getValue();
        }  else {
            end = x.endExpr.toString();
        }
        Column column = getColumn(x);
        if (column == null) {
            return true;
        }

        Condition condition = null;
        for (Condition item : this.getConditions()) {
            if (item.getColumn().equals(column) && item.getOperator().equals("between")) {
                condition = item;
                break;
            }
        }

        if (condition == null) {
            condition = new Condition();
            condition.setColumn(column);
            condition.setOperator("between");
            this.conditions.add(condition);
        }


        condition.getValues().add(begin);
        condition.getValues().add(end);


        return true;
    }

    @Override
    protected Column getColumn(SQLExpr expr) {
        Map<String, String> aliasMap = getAliasMap();
        if (aliasMap == null) {
            return null;
        }

        if (expr instanceof SQLPropertyExpr) {
            SQLExpr owner = ((SQLPropertyExpr) expr).getOwner();
            String column = ((SQLPropertyExpr) expr).getName();

            if (owner instanceof SQLIdentifierExpr) {
                String tableName = ((SQLIdentifierExpr) owner).getName();
                String table = tableName;
                if (aliasMap.containsKey(table)) {
                    table = aliasMap.get(table);
                }

                if (variants.containsKey(table)) {
                    return null;
                }

                if (table != null) {
                    return new Column(table, column);
                }

                return handleSubQueryColumn(tableName, column);
            }

            return null;
        }

        if (expr instanceof SQLIdentifierExpr) {
            Column attrColumn = (Column) expr.getAttribute(ATTR_COLUMN);
            if (attrColumn != null) {
                return attrColumn;
            }

            String column = ((SQLIdentifierExpr) expr).getName();
            String table = getCurrentTable();
            if (table != null && aliasMap.containsKey(table)) {
                table = aliasMap.get(table);
                if (table == null) {
                    return null;
                }
            }

            if (table != null) {
                return new Column(table, column);
            }

            if (variants.containsKey(column)) {
                return null;
            }

            return new Column("UNKNOWN", column);
        }

        if(expr instanceof SQLBetweenExpr) {
            SQLBetweenExpr betweenExpr = (SQLBetweenExpr)expr;

            if(betweenExpr.getTestExpr() != null) {
                String tableName = null;
                String column = null;
                if(betweenExpr.getTestExpr() instanceof SQLPropertyExpr) {//??????????????????
                    tableName = ((SQLIdentifierExpr)((SQLPropertyExpr) betweenExpr.getTestExpr()).getOwner()).getName();
                    column = ((SQLPropertyExpr) betweenExpr.getTestExpr()).getName();
					SQLObject query = this.subQueryMap.get(tableName);
					if(query == null) {
						if (aliasMap.containsKey(tableName)) {
							tableName = aliasMap.get(tableName);
						}
						return new Column(tableName, column);
					}
                    return handleSubQueryColumn(tableName, column);
                } else if(betweenExpr.getTestExpr() instanceof SQLIdentifierExpr) {
                    column = ((SQLIdentifierExpr) betweenExpr.getTestExpr()).getName();
                    //?????????????????????,????????????????????????????????????ambiguous???
                    //??????????????????????????????????????????,fdbparser??????defaultTable??????join?????????leftTable
                    tableName = getOwnerTableName(betweenExpr,column);
                }
                String table = tableName;
                if (aliasMap.containsKey(table)) {
                    table = aliasMap.get(table);
                }

                if (variants.containsKey(table)) {
                    return null;
                }

                if (table != null&&!"".equals(table)) {
                    return new Column(table, column);
                }
            }


        }
        return null;
    }

    /**
     * ???between???????????????????????????????????????
     * ??????????????????ambiguous????????????????????????????????????????????????????????????????????????????????????????????????
     * @param betweenExpr
     * @param column
     * @return
     */
    private String getOwnerTableName(SQLBetweenExpr betweenExpr,String column) {
        if(tableStats.size() == 1) {//?????????????????????????????????????????????
            return tableStats.keySet().iterator().next().getName();
        } else if(tableStats.size() == 0) {//?????????????????????????????????
            return "";
        } else {//????????????
            for (Column col : columns.keySet())
            {
                if(col.getName().equals(column)) {
                    return col.getTable();
                }
            }
//            for(Column col : columns) {//???columns????????????
//                if(col.getName().equals(column)) {
//                    return col.getTable();
//                }
//            }

            //????????????????????????????????????parent?????????

            SQLObject parent = betweenExpr.getParent();
            if(parent instanceof SQLBinaryOpExpr)
            {
                parent=parent.getParent();
            }

            if(parent instanceof MySqlSelectQueryBlock) {
                MySqlSelectQueryBlock select = (MySqlSelectQueryBlock) parent;
                if(select.getFrom() instanceof SQLJoinTableSource) {//????????????
                    SQLJoinTableSource joinTableSource = (SQLJoinTableSource)select.getFrom();
                    return joinTableSource.getLeft().toString();//???left?????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????????
                } else if(select.getFrom() instanceof SQLExprTableSource) {//??????
                    return select.getFrom().toString();
                }
            }
            else if(parent instanceof SQLUpdateStatement) {
                SQLUpdateStatement update = (SQLUpdateStatement) parent;
                return update.getTableName().getSimpleName();
            } else if(parent instanceof SQLDeleteStatement) {
                SQLDeleteStatement delete = (SQLDeleteStatement) parent;
                return delete.getTableName().getSimpleName();
            } else {
                
            }
        }
        return "";
    }
    
    private void setSubQueryRelationOrFlag(SQLExprImpl x){
    	MycatSubQueryVisitor subQueryVisitor = new MycatSubQueryVisitor();
    	x.accept(subQueryVisitor);
    	if(subQueryVisitor.isRelationOr()){
    		subqueryRelationOr = true;
    	}
    }
    
    /*
     * ?????????
     * (non-Javadoc)
     * @see com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter#visit(com.alibaba.druid.sql.ast.expr.SQLQueryExpr)
     */
    @Override
    public boolean visit(SQLQueryExpr x) {
    	setSubQueryRelationOrFlag(x);
    	addSubQuerys(x.getSubQuery());
    	return super.visit(x);
    }
    /*
     * (non-Javadoc)
     * @see com.alibaba.druid.sql.visitor.SchemaStatVisitor#visit(com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource)
     */
    @Override
    public boolean visit(SQLSubqueryTableSource x){
    	addSubQuerys(x.getSelect());
    	return super.visit(x);
    }
    
    /*
     * (non-Javadoc)
     * @see com.alibaba.druid.sql.visitor.SQLASTVisitorAdapter#visit(com.alibaba.druid.sql.ast.expr.SQLExistsExpr)
     */
    @Override
    public boolean visit(SQLExistsExpr x) {
    	setSubQueryRelationOrFlag(x);
    	addSubQuerys(x.getSubQuery());
    	return super.visit(x);
    }
    
    @Override
    public boolean visit(SQLInListExpr x) {
    	return super.visit(x);
    }
    
    /*
     *  ??? in ??????????????????
     * (non-Javadoc)
     * @see com.alibaba.druid.sql.visitor.SchemaStatVisitor#visit(com.alibaba.druid.sql.ast.expr.SQLInSubQueryExpr)
     */
    @Override
    public boolean visit(SQLInSubQueryExpr x) {
    	setSubQueryRelationOrFlag(x);
    	addSubQuerys(x.getSubQuery());
    	return super.visit(x);
    }
    
    /* 
     *  ?????? all ?????????????????????  SELECT MAX(name) FROM subtest1
     *  ??????:
     *        select * from subtest where id > all (select name from subtest1);
     *    		>/>= all ----> >/>= max
     *    		</<= all ----> </<= min
     *    		<>   all ----> not in
     *          =    all ----> id = 1 and id = 2
     *          other  ?????????
     */    
    @Override
    public boolean visit(SQLAllExpr x) {
    	setSubQueryRelationOrFlag(x);
    	
    	List<SQLSelectItem> itemlist = ((SQLSelectQueryBlock)(x.getSubQuery().getQuery())).getSelectList();
    	SQLExpr sexpr = itemlist.get(0).getExpr();
    	
		if(x.getParent() instanceof SQLBinaryOpExpr){
			SQLBinaryOpExpr parentExpr = (SQLBinaryOpExpr)x.getParent();
			SQLAggregateExpr saexpr = null;
			switch (parentExpr.getOperator()) {
			case GreaterThan:
			case GreaterThanOrEqual:
			case NotLessThan:
				this.hasChange = true;
				if(sexpr instanceof SQLIdentifierExpr 
						|| (sexpr instanceof SQLPropertyExpr&&((SQLPropertyExpr)sexpr).getOwner() instanceof SQLIdentifierExpr)){
					saexpr = new SQLAggregateExpr("MAX");
					saexpr.getArguments().add(sexpr);
	        		saexpr.setParent(itemlist.get(0));
	        		itemlist.get(0).setExpr(saexpr);
				}
				SQLQueryExpr maxSubQuery = new SQLQueryExpr(x.getSubQuery());
        		x.getSubQuery().setParent(x.getParent());
        		// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
            	if(x.getParent() instanceof SQLBinaryOpExpr){
            		if(((SQLBinaryOpExpr)x.getParent()).getLeft().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setLeft(maxSubQuery);
            		}else if(((SQLBinaryOpExpr)x.getParent()).getRight().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setRight(maxSubQuery);
            		}
            	}
            	addSubQuerys(x.getSubQuery());
            	return super.visit(x.getSubQuery());
			case LessThan:
			case LessThanOrEqual:
			case NotGreaterThan:
				this.hasChange = true;
				if(sexpr instanceof SQLIdentifierExpr 
						|| (sexpr instanceof SQLPropertyExpr&&((SQLPropertyExpr)sexpr).getOwner() instanceof SQLIdentifierExpr)){
					saexpr = new SQLAggregateExpr("MIN");
					saexpr.getArguments().add(sexpr);
	        		saexpr.setParent(itemlist.get(0));
	        		itemlist.get(0).setExpr(saexpr);
	        		
	            	x.subQuery.setParent(x.getParent());
				}
				// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
            	SQLQueryExpr minSubQuery = new SQLQueryExpr(x.getSubQuery());
            	if(x.getParent() instanceof SQLBinaryOpExpr){
            		if(((SQLBinaryOpExpr)x.getParent()).getLeft().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setLeft(minSubQuery);
            		}else if(((SQLBinaryOpExpr)x.getParent()).getRight().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setRight(minSubQuery);
            		}
            	}
            	addSubQuerys(x.getSubQuery());
            	return super.visit(x.getSubQuery());
			 case LessThanOrGreater:
			 case NotEqual:
				this.hasChange = true;
				SQLInSubQueryExpr notInSubQueryExpr = new SQLInSubQueryExpr(x.getSubQuery());
				x.getSubQuery().setParent(notInSubQueryExpr);
				notInSubQueryExpr.setNot(true);
				// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
				if(x.getParent() instanceof SQLBinaryOpExpr){
					SQLBinaryOpExpr xp = (SQLBinaryOpExpr)x.getParent();
					
					if(xp.getLeft().equals(x)){
						notInSubQueryExpr.setExpr(xp.getRight());
					}else if(xp.getRight().equals(x)){
						notInSubQueryExpr.setExpr(xp.getLeft());
					}
					
					if(xp.getParent() instanceof MySqlSelectQueryBlock){
						((MySqlSelectQueryBlock)xp.getParent()).setWhere(notInSubQueryExpr);
					}else if(xp.getParent() instanceof SQLBinaryOpExpr){
						SQLBinaryOpExpr pp = ((SQLBinaryOpExpr)xp.getParent());
						if(pp.getLeft().equals(xp)){
							pp.setLeft(notInSubQueryExpr);
						}else if(pp.getRight().equals(xp)){
							pp.setRight(notInSubQueryExpr);
						}
					}
	            }
				addSubQuerys(x.getSubQuery());
	            return super.visit(notInSubQueryExpr);
			 default:
				break;
			}
		}
		addSubQuerys(x.getSubQuery());
    	return super.visit(x);
    }
    
    /* 
     *  ?????? some ?????????????????????  SELECT MIN(name) FROM subtest1
     *  ??????:
     *        select * from subtest where id > some (select name from subtest1);
     *    >/>= some ----> >/>= min
     *    </<= some ----> </<= max
     *    <>   some ----> not in
     *    =    some ----> in
     *    other  ?????????
     */
    @Override
    public boolean visit(SQLSomeExpr x) {
    	
    	setSubQueryRelationOrFlag(x);
    	
    	List<SQLSelectItem> itemlist = ((SQLSelectQueryBlock)(x.getSubQuery().getQuery())).getSelectList();
    	SQLExpr sexpr = itemlist.get(0).getExpr();
    	
		if(x.getParent() instanceof SQLBinaryOpExpr){
			SQLBinaryOpExpr parentExpr = (SQLBinaryOpExpr)x.getParent();
			SQLAggregateExpr saexpr = null;
			switch (parentExpr.getOperator()) {
			case GreaterThan:
			case GreaterThanOrEqual:
			case NotLessThan:
				this.hasChange = true;
				if(sexpr instanceof SQLIdentifierExpr 
						|| (sexpr instanceof SQLPropertyExpr&&((SQLPropertyExpr)sexpr).getOwner() instanceof SQLIdentifierExpr)){
					saexpr = new SQLAggregateExpr("MIN");
					saexpr.getArguments().add(sexpr);
	        		saexpr.setParent(itemlist.get(0));
	        		itemlist.get(0).setExpr(saexpr);
				}
				SQLQueryExpr maxSubQuery = new SQLQueryExpr(x.getSubQuery());
        		x.getSubQuery().setParent(maxSubQuery);
        		// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
            	if(x.getParent() instanceof SQLBinaryOpExpr){
            		if(((SQLBinaryOpExpr)x.getParent()).getLeft().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setLeft(maxSubQuery);
            		}else if(((SQLBinaryOpExpr)x.getParent()).getRight().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setRight(maxSubQuery);
            		}
            	}
            	addSubQuerys(x.getSubQuery());
            	return super.visit(x.getSubQuery());
			case LessThan:
			case LessThanOrEqual:
			case NotGreaterThan:
				this.hasChange = true;
				if(sexpr instanceof SQLIdentifierExpr 
						|| (sexpr instanceof SQLPropertyExpr&&((SQLPropertyExpr)sexpr).getOwner() instanceof SQLIdentifierExpr)){
					saexpr = new SQLAggregateExpr("MAX");
					saexpr.getArguments().add(sexpr);
	        		saexpr.setParent(itemlist.get(0));
	        		itemlist.get(0).setExpr(saexpr);
				}
				// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
            	SQLQueryExpr minSubQuery = new SQLQueryExpr(x.getSubQuery());
        		x.getSubQuery().setParent(minSubQuery);
            	
            	if(x.getParent() instanceof SQLBinaryOpExpr){
            		if(((SQLBinaryOpExpr)x.getParent()).getLeft().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setLeft(minSubQuery);
            		}else if(((SQLBinaryOpExpr)x.getParent()).getRight().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setRight(minSubQuery);
            		}
            	}
            	addSubQuerys(x.getSubQuery());
            	return super.visit(x.getSubQuery());
			 case LessThanOrGreater:
			 case NotEqual:
				 this.hasChange = true;
					SQLInSubQueryExpr notInSubQueryExpr = new SQLInSubQueryExpr(x.getSubQuery());
					x.getSubQuery().setParent(notInSubQueryExpr);
					notInSubQueryExpr.setNot(true);
					// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
					if(x.getParent() instanceof SQLBinaryOpExpr){
						SQLBinaryOpExpr xp = (SQLBinaryOpExpr)x.getParent();
						
						if(xp.getLeft().equals(x)){
							notInSubQueryExpr.setExpr(xp.getRight());
						}else if(xp.getRight().equals(x)){
							notInSubQueryExpr.setExpr(xp.getLeft());
						}
						
						if(xp.getParent() instanceof MySqlSelectQueryBlock){
							((MySqlSelectQueryBlock)xp.getParent()).setWhere(notInSubQueryExpr);
						}else if(xp.getParent() instanceof SQLBinaryOpExpr){
							SQLBinaryOpExpr pp = ((SQLBinaryOpExpr)xp.getParent());
							if(pp.getLeft().equals(xp)){
								pp.setLeft(notInSubQueryExpr);
							}else if(pp.getRight().equals(xp)){
								pp.setRight(notInSubQueryExpr);
							}
						}
		            }
					addSubQuerys(x.getSubQuery());
		            return super.visit(notInSubQueryExpr);
			 case Equality:
				 this.hasChange = true;
				SQLInSubQueryExpr inSubQueryExpr = new SQLInSubQueryExpr(x.getSubQuery());
				x.getSubQuery().setParent(inSubQueryExpr);
				inSubQueryExpr.setNot(false);
				// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
				if(x.getParent() instanceof SQLBinaryOpExpr){
					SQLBinaryOpExpr xp = (SQLBinaryOpExpr)x.getParent();
					
					if(xp.getLeft().equals(x)){
						inSubQueryExpr.setExpr(xp.getRight());
					}else if(xp.getRight().equals(x)){
						inSubQueryExpr.setExpr(xp.getLeft());
					}
					
					if(xp.getParent() instanceof MySqlSelectQueryBlock){
						((MySqlSelectQueryBlock)xp.getParent()).setWhere(inSubQueryExpr);
					}else if(xp.getParent() instanceof SQLBinaryOpExpr){
						SQLBinaryOpExpr pp = ((SQLBinaryOpExpr)xp.getParent());
						if(pp.getLeft().equals(xp)){
							pp.setLeft(inSubQueryExpr);
						}else if(pp.getRight().equals(xp)){
							pp.setRight(inSubQueryExpr);
						}
					}
	            }
				addSubQuerys(x.getSubQuery());
	            return super.visit(inSubQueryExpr);
			 default:
				break;
			}
		}
		addSubQuerys(x.getSubQuery());
    	return super.visit(x);
    }

    /* 
     *  ?????? any ?????????????????????  SELECT MIN(name) FROM subtest1
     *  ??????:
     *    select * from subtest where id oper any (select name from subtest1);
     *    >/>= any ----> >/>= min
     *    </<= any ----> </<= max
     *    <>   any ----> not in
     *    =    some ----> in
     *    other  ?????????
     */
    @Override
    public boolean visit(SQLAnyExpr x) {
    	
    	setSubQueryRelationOrFlag(x);
    	
    	List<SQLSelectItem> itemlist = ((SQLSelectQueryBlock)(x.getSubQuery().getQuery())).getSelectList();
    	SQLExpr sexpr = itemlist.get(0).getExpr();
    	
		if(x.getParent() instanceof SQLBinaryOpExpr){
			SQLBinaryOpExpr parentExpr = (SQLBinaryOpExpr)x.getParent();
			SQLAggregateExpr saexpr = null;
			switch (parentExpr.getOperator()) {
			case GreaterThan:
			case GreaterThanOrEqual:
			case NotLessThan:
				this.hasChange = true;
				if(sexpr instanceof SQLIdentifierExpr 
						|| (sexpr instanceof SQLPropertyExpr&&((SQLPropertyExpr)sexpr).getOwner() instanceof SQLIdentifierExpr)){
					saexpr = new SQLAggregateExpr("MIN");
					saexpr.getArguments().add(sexpr);
	        		saexpr.setParent(itemlist.get(0));
	        		itemlist.get(0).setExpr(saexpr);
				}
				SQLQueryExpr maxSubQuery = new SQLQueryExpr(x.getSubQuery());
        		x.getSubQuery().setParent(maxSubQuery);
				// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
            	if(x.getParent() instanceof SQLBinaryOpExpr){
            		if(((SQLBinaryOpExpr)x.getParent()).getLeft().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setLeft(maxSubQuery);
            		}else if(((SQLBinaryOpExpr)x.getParent()).getRight().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setRight(maxSubQuery);
            		}
            	}
            	addSubQuerys(x.getSubQuery());
            	return super.visit(x.getSubQuery());
			case LessThan:
			case LessThanOrEqual:
			case NotGreaterThan:
				this.hasChange = true;
				if(sexpr instanceof SQLIdentifierExpr 
						|| (sexpr instanceof SQLPropertyExpr&&((SQLPropertyExpr)sexpr).getOwner() instanceof SQLIdentifierExpr)){
					saexpr = new SQLAggregateExpr("MAX");
					saexpr.getArguments().add(sexpr);
	        		saexpr.setParent(itemlist.get(0));
	        		itemlist.get(0).setExpr(saexpr);
				}
				// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
            	SQLQueryExpr minSubQuery = new SQLQueryExpr(x.getSubQuery());
            	x.subQuery.setParent(minSubQuery);
            	if(x.getParent() instanceof SQLBinaryOpExpr){
            		if(((SQLBinaryOpExpr)x.getParent()).getLeft().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setLeft(minSubQuery);
            		}else if(((SQLBinaryOpExpr)x.getParent()).getRight().equals(x)){
            			((SQLBinaryOpExpr)x.getParent()).setRight(minSubQuery);
            		}
            	}
            	addSubQuerys(x.getSubQuery());
            	return super.visit(x.getSubQuery());
			 case LessThanOrGreater:
			 case NotEqual:
				 this.hasChange = true;
					SQLInSubQueryExpr notInSubQueryExpr = new SQLInSubQueryExpr(x.getSubQuery());
					x.getSubQuery().setParent(notInSubQueryExpr);
					notInSubQueryExpr.setNot(true);
					// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
					if(x.getParent() instanceof SQLBinaryOpExpr){
						SQLBinaryOpExpr xp = (SQLBinaryOpExpr)x.getParent();
						
						if(xp.getLeft().equals(x)){
							notInSubQueryExpr.setExpr(xp.getRight());
						}else if(xp.getRight().equals(x)){
							notInSubQueryExpr.setExpr(xp.getLeft());
						}
						
						if(xp.getParent() instanceof MySqlSelectQueryBlock){
							((MySqlSelectQueryBlock)xp.getParent()).setWhere(notInSubQueryExpr);
						}else if(xp.getParent() instanceof SQLBinaryOpExpr){
							SQLBinaryOpExpr pp = ((SQLBinaryOpExpr)xp.getParent());
							if(pp.getLeft().equals(xp)){
								pp.setLeft(notInSubQueryExpr);
							}else if(pp.getRight().equals(xp)){
								pp.setRight(notInSubQueryExpr);
							}
						}
		            }
					addSubQuerys(x.getSubQuery());
		            return super.visit(notInSubQueryExpr);
			 case Equality:
				 this.hasChange = true;
				SQLInSubQueryExpr inSubQueryExpr = new SQLInSubQueryExpr(x.getSubQuery());
				x.getSubQuery().setParent(inSubQueryExpr);
				inSubQueryExpr.setNot(false);
				// ????????????SQLQueryExpr ???????????? SQLAllExpr ??????
				if(x.getParent() instanceof SQLBinaryOpExpr){
					SQLBinaryOpExpr xp = (SQLBinaryOpExpr)x.getParent();
					
					if(xp.getLeft().equals(x)){
						inSubQueryExpr.setExpr(xp.getRight());
					}else if(xp.getRight().equals(x)){
						inSubQueryExpr.setExpr(xp.getLeft());
					}
					
					if(xp.getParent() instanceof MySqlSelectQueryBlock){
						((MySqlSelectQueryBlock)xp.getParent()).setWhere(inSubQueryExpr);
					}else if(xp.getParent() instanceof SQLBinaryOpExpr){
						SQLBinaryOpExpr pp = ((SQLBinaryOpExpr)xp.getParent());
						if(pp.getLeft().equals(xp)){
							pp.setLeft(inSubQueryExpr);
						}else if(pp.getRight().equals(xp)){
							pp.setRight(inSubQueryExpr);
						}
					}
	            }
				addSubQuerys(x.getSubQuery());
	            return super.visit(inSubQueryExpr);
			 default:
				break;
			}
		}
		addSubQuerys(x.getSubQuery());
    	return super.visit(x);
    }
    
    @Override
	public boolean visit(SQLBinaryOpExpr x) {
        x.getLeft().setParent(x);
        x.getRight().setParent(x);
        
        /*
         * fix bug ??? selectlist ????????????????????????, ??????????????????????????????.????????????????????? ?????????????????????????????????.
         *  eg. select (select id from subtest2 where id = 1), (select id from subtest3 where id = 2) from subtest1 where id =4;
         *  ?????????????????????, subtest1 ??? ????????????  id = 4 .  ??? ?????????  subtest3 ???. ???????????????????????????,????????????,????????????????????????.
         *  ???????????????????????????????????????,???????????????.
         */
        String currenttable = x.getParent()==null?null: (String) x.getParent().getAttribute(SchemaStatVisitor.ATTR_TABLE);
        if(currenttable!=null){
        	this.setCurrentTable(currenttable);
        }
        
        switch (x.getOperator()) {
            case Equality:
            case LessThanOrEqualOrGreaterThan:
            case Is:
            case IsNot:
            case GreaterThan:
            case GreaterThanOrEqual:
            case LessThan:
            case LessThanOrEqual:
            case NotLessThan:
            case LessThanOrGreater:
			case NotEqual:
			case NotGreaterThan:
                handleCondition(x.getLeft(), x.getOperator().name, x.getRight());
                handleCondition(x.getRight(), x.getOperator().name, x.getLeft());
                handleRelationship(x.getLeft(), x.getOperator().name, x.getRight());
                break;
            case BooleanOr:
            	//???????????????where????????????
            	if(!RouterUtil.isConditionAlwaysTrue(x)) {
            		hasOrCondition = true;
            		
            		WhereUnit whereUnit = null;
            		if(conditions.size() > 0) {
            			whereUnit = new WhereUnit();
            			whereUnit.setFinishedParse(true);
            			whereUnit.addOutConditions(getConditions());
            			WhereUnit innerWhereUnit = new WhereUnit(x);
            			whereUnit.addSubWhereUnit(innerWhereUnit);
            		} else {
            			whereUnit = new WhereUnit(x);
            			whereUnit.addOutConditions(getConditions());
            		}
            		whereUnits.add(whereUnit);
            	}
            	return false;
            case Like:
            case NotLike:
            default:
                break;
        }
        return true;
    }
	
	/**
	 * ????????????
	 */
	public List<List<Condition>> splitConditions() {
		//??????or??????
		for(WhereUnit whereUnit : whereUnits) {
			splitUntilNoOr(whereUnit);
		}
		
		this.storedwhereUnits.addAll(whereUnits);
		
		loopFindSubWhereUnit(whereUnits);
		
		//??????????????????????????????Condition??????
		for(WhereUnit whereUnit : storedwhereUnits) {
			this.getConditionsFromWhereUnit(whereUnit);
		}
		
		//??????WhereUnit??????:?????????????????????
		return mergedConditions();
	}
	
	/**
	 * ???????????????WhereUnit?????????????????????or???
	 * @param whereUnitList
	 */
	private void loopFindSubWhereUnit(List<WhereUnit> whereUnitList) {
		List<WhereUnit> subWhereUnits = new ArrayList<WhereUnit>();
		for(WhereUnit whereUnit : whereUnitList) {
			if(whereUnit.getSplitedExprList().size() > 0) {
				List<SQLExpr> removeSplitedList = new ArrayList<SQLExpr>();
				for(SQLExpr sqlExpr : whereUnit.getSplitedExprList()) {
					reset();
					if(isExprHasOr(sqlExpr)) {
						removeSplitedList.add(sqlExpr);
						WhereUnit subWhereUnit = this.whereUnits.get(0);
						splitUntilNoOr(subWhereUnit);
						whereUnit.addSubWhereUnit(subWhereUnit);
						subWhereUnits.add(subWhereUnit);
					} else {
						this.conditions.clear();
					}
				}
				if(removeSplitedList.size() > 0) {
					whereUnit.getSplitedExprList().removeAll(removeSplitedList);
				}
			}
			subWhereUnits.addAll(whereUnit.getSubWhereUnit());
		}
		if(subWhereUnits.size() > 0) {
			loopFindSubWhereUnit(subWhereUnits);
		}
	}
	
	private boolean isExprHasOr(SQLExpr expr) {
		expr.accept(this);
		return hasOrCondition;
	}
	
	private List<List<Condition>> mergedConditions() {
		if(storedwhereUnits.size() == 0) {
			return new ArrayList<List<Condition>>();
		}
		for(WhereUnit whereUnit : storedwhereUnits) {
			mergeOneWhereUnit(whereUnit);
		}
		return getMergedConditionList(storedwhereUnits);
		
	}
	
	/**
	 * ??????WhereUnit?????????
	 * @param whereUnit
	 */
	private void mergeOneWhereUnit(WhereUnit whereUnit) {
		if(whereUnit.getSubWhereUnit().size() > 0) {
			for(WhereUnit sub : whereUnit.getSubWhereUnit()) {
				mergeOneWhereUnit(sub);
			}
			
			if(whereUnit.getSubWhereUnit().size() > 1) {
				List<List<Condition>> mergedConditionList = getMergedConditionList(whereUnit.getSubWhereUnit());
				if(whereUnit.getOutConditions().size() > 0) {
					for(int i = 0; i < mergedConditionList.size() ; i++) {
						mergedConditionList.get(i).addAll(whereUnit.getOutConditions());
					}
				}
				whereUnit.setConditionList(mergedConditionList);
			} else if(whereUnit.getSubWhereUnit().size() == 1) {
				if(whereUnit.getOutConditions().size() > 0 && whereUnit.getSubWhereUnit().get(0).getConditionList().size() > 0) {
					for(int i = 0; i < whereUnit.getSubWhereUnit().get(0).getConditionList().size() ; i++) {
						whereUnit.getSubWhereUnit().get(0).getConditionList().get(i).addAll(whereUnit.getOutConditions());
					}
				}
				whereUnit.getConditionList().addAll(whereUnit.getSubWhereUnit().get(0).getConditionList());
			}
		} else {
			//do nothing
		}
	}
	
	/**
	 * ?????????????????????WhereUnit??????????????????
	 * @return
	 */
	private List<List<Condition>> getMergedConditionList(List<WhereUnit> whereUnitList) {
		List<List<Condition>> mergedConditionList = new ArrayList<List<Condition>>();
		if(whereUnitList.size() == 0) {
			return mergedConditionList; 
		}
		mergedConditionList.addAll(whereUnitList.get(0).getConditionList());
		
		for(int i = 1; i < whereUnitList.size(); i++) {
			mergedConditionList = merge(mergedConditionList, whereUnitList.get(i).getConditionList());
		}
		return mergedConditionList;
	}
	
	/**
	 * ??????list??????????????????
	 * @param list1
	 * @param list2
	 * @return
	 */
	private List<List<Condition>> merge(List<List<Condition>> list1, List<List<Condition>> list2) {
		if(list1.size() == 0) {
			return list2;
		} else if (list2.size() == 0) {
			return list1;
		}
		
		List<List<Condition>> retList = new ArrayList<List<Condition>>();
		for(int i = 0; i < list1.size(); i++) {
			for(int j = 0; j < list2.size(); j++) {
				List<Condition> listTmp = new ArrayList<Condition>();
				listTmp.addAll(list1.get(i));
				listTmp.addAll(list2.get(j));
				retList.add(listTmp);
			}
		}
		return retList;
	}
	
	private void getConditionsFromWhereUnit(WhereUnit whereUnit) {
		List<List<Condition>> retList = new ArrayList<List<Condition>>();
		//or?????????????????????:???where condition1 and (condition2 or condition3),condition1????????????????????????,??????????????????
		List<Condition> outSideCondition = new ArrayList<Condition>();
//		stashOutSideConditions();
		outSideCondition.addAll(conditions);
		this.conditions.clear();
		for(SQLExpr sqlExpr : whereUnit.getSplitedExprList()) {
			sqlExpr.accept(this);
			List<Condition> conditions = new ArrayList<Condition>();
			conditions.addAll(getConditions());
			conditions.addAll(outSideCondition);
			retList.add(conditions);
			this.conditions.clear();
		}
		whereUnit.setConditionList(retList);
		
		for(WhereUnit subWhere : whereUnit.getSubWhereUnit()) {
			getConditionsFromWhereUnit(subWhere);
		}
	}
	
	/**
	 * ????????????OR
	 * 
	 * @param whereUnit
	 * TODO:????????????or????????????????????????????????? exists???????????????????????????????????????
	 */
	private void splitUntilNoOr(WhereUnit whereUnit) {
		if(whereUnit.isFinishedParse()) {
			if(whereUnit.getSubWhereUnit().size() > 0) {
				for(int i = 0; i < whereUnit.getSubWhereUnit().size(); i++) {
					splitUntilNoOr(whereUnit.getSubWhereUnit().get(i));
				}
			} 
		} else {
			SQLBinaryOpExpr expr = whereUnit.getCanSplitExpr();
			if(expr.getOperator() == SQLBinaryOperator.BooleanOr) {
//				whereUnit.addSplitedExpr(expr.getRight());
				addExprIfNotFalse(whereUnit, expr.getRight());
				if(expr.getLeft() instanceof SQLBinaryOpExpr) {
					whereUnit.setCanSplitExpr((SQLBinaryOpExpr)expr.getLeft());
					splitUntilNoOr(whereUnit);
				} else {
					addExprIfNotFalse(whereUnit, expr.getLeft());
				}
			} else {
				addExprIfNotFalse(whereUnit, expr);
				whereUnit.setFinishedParse(true);
			}
		}
    }

	private void addExprIfNotFalse(WhereUnit whereUnit, SQLExpr expr) {
		//?????????????????????????????????
		if(!RouterUtil.isConditionAlwaysFalse(expr)) {
			whereUnit.addSplitedExpr(expr);
		}
	}
	
	@Override
    public boolean visit(SQLAlterTableStatement x) {
        String tableName = x.getName().toString();
        TableStat stat = getTableStat(tableName,tableName);
        stat.incrementAlterCount();

        setCurrentTable(x, tableName);

        for (SQLAlterTableItem item : x.getItems()) {
            item.setParent(x);
            item.accept(this);
        }

        return false;
    }
    public boolean visit(MySqlCreateTableStatement x) {
        SQLName sqlName=  x.getName();
        if(sqlName!=null)
        {
            String table = sqlName.toString();
            if(table.startsWith("`"))
            {
                table=table.substring(1,table.length()-1);
            }
            setCurrentTable(table);
        }
        return false;
    }
    public boolean visit(MySqlInsertStatement x) {
        SQLName sqlName=  x.getTableName();
        if(sqlName!=null)
        {
            String table = sqlName.toString();
            if(table.startsWith("`"))
            {
                table=table.substring(1,table.length()-1);
            }
            setCurrentTable(sqlName.toString());
        }
        return false;
    }
	// DUAL
    public boolean visit(MySqlDeleteStatement x) {
        setAliasMap();

        setMode(x, Mode.Delete);

        accept(x.getFrom());
        accept(x.getUsing());
        x.getTableSource().accept(this);

        if (x.getTableSource() instanceof SQLExprTableSource) {
            SQLName tableName = (SQLName) ((SQLExprTableSource) x.getTableSource()).getExpr();
            String ident = tableName.toString();
            setCurrentTable(x, ident);

            TableStat stat = this.getTableStat(ident,ident);
            stat.incrementDeleteCount();
        }

        accept(x.getWhere());

        accept(x.getOrderBy());
        accept(x.getLimit());

        return false;
    }
    
    public void endVisit(MySqlDeleteStatement x) {
    }
    
    public boolean visit(SQLUpdateStatement x) {
        setAliasMap();

        setMode(x, Mode.Update);

        SQLName identName = x.getTableName();
        if (identName != null) {
            String ident = identName.toString();
            String alias = x.getTableSource().getAlias();
            setCurrentTable(ident);

            TableStat stat = getTableStat(ident);
            stat.incrementUpdateCount();

            Map<String, String> aliasMap = getAliasMap();
            
            aliasMap.put(ident, ident);
            if(alias != null) {
            	aliasMap.put(alias, ident);
            }
        } else {
            x.getTableSource().accept(this);
        }

        accept(x.getItems());
        accept(x.getWhere());

        return false;
    }
    
    @Override
    public void endVisit(MySqlHintStatement x) {
    	super.endVisit(x);
    }
    
    @Override
    public boolean visit(MySqlHintStatement x) {
    	List<SQLCommentHint> hits = x.getHints();
    	if(hits != null && !hits.isEmpty()) {
    		String schema = parseSchema(hits);
    		if(schema != null ) {
    			setCurrentTable(x, schema + ".");
    			return true;
    		}
    	}
    	return true;
    }
    
    private String parseSchema(List<SQLCommentHint> hits) {
    	String regx = "\\!mycat:schema\\s*=([\\s\\w]*)$";
    	for(SQLCommentHint hit : hits ) {
    		Pattern pattern = Pattern.compile(regx);
    		Matcher m = pattern.matcher(hit.getText());
    		if(m.matches()) {
    			return m.group(1).trim();
    		}
    	}
		return null;
    }

	public List<SQLSelect> getSubQuerys() {
		return subQuerys;
	}
	
	private void addSubQuerys(SQLSelect sqlselect){
		/* ?????? sqlselect ??????  , equals ??? hashcode ????????????.????????? ??????????????????. */
		if(subQuerys.isEmpty()){
			subQuerys.add(sqlselect);
			return;
		}
		Iterator<SQLSelect> iter = subQuerys.iterator();
		while(iter.hasNext()){
			SQLSelect ss = iter.next();
			if(ss.getQuery() instanceof SQLSelectQueryBlock
					&&sqlselect.getQuery() instanceof SQLSelectQueryBlock){
				SQLSelectQueryBlock current = (SQLSelectQueryBlock)sqlselect.getQuery();
				SQLSelectQueryBlock ssqb = (SQLSelectQueryBlock)ss.getQuery();

				if(!sqlSelectQueryBlockEquals(ssqb,current)){
					subQuerys.add(sqlselect);
				}
			}
		}
	}
	
	/* ?????? sqlselect ??????  , equals ??? hashcode ????????????.????????? ?????? SQLSelectQueryBlock equals ?????? */
    private boolean sqlSelectQueryBlockEquals(SQLSelectQueryBlock obj1,SQLSelectQueryBlock obj2) {
        if (obj1 == obj2) return true;
        if (obj2 == null) return false;
        if (obj1.getClass() != obj2.getClass()) return false;
        if (obj1.isParenthesized() ^ obj2.isParenthesized()) return false;
        if (obj1.getDistionOption() != obj2.getDistionOption()) return false;
        if (obj1.getFrom() == null) {
            if (obj2.getFrom() != null) return false;
        } else if (!obj1.getFrom().equals(obj2.getFrom())) return false;
        if (obj1.getGroupBy() == null) {
            if (obj2.getGroupBy() != null) return false;
        } else if (!obj1.getGroupBy().equals(obj2.getGroupBy())) return false;
        if (obj1.getInto() == null) {
            if (obj2.getInto() != null) return false;
        } else if (!obj1.getInto().equals(obj2.getInto())) return false;
        if (obj1.getSelectList() == null) {
            if (obj2.getSelectList() != null) return false;
        } else if (!obj1.getSelectList().equals(obj2.getSelectList())) return false;
        if (obj1.getWhere() == null) {
            if (obj2.getWhere() != null) return false;
        } else if (!obj1.getWhere().equals(obj2.getWhere())) return false;
        return true;
    }

	public boolean isHasChange() {
		return hasChange;
	}

	public boolean isSubqueryRelationOr() {
		return subqueryRelationOr;
	}
}
