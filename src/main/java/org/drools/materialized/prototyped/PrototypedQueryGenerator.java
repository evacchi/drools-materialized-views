package org.drools.materialized.prototyped;

import java.util.HashMap;
import java.util.Map;

import org.apache.calcite.rel.RelRoot;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.drools.core.base.accumulators.CountAccumulateFunction;
import org.drools.model.DSL;
import org.drools.model.Index;
import org.drools.model.Model;
import org.drools.model.Prototype;
import org.drools.model.PrototypeDSL;
import org.drools.model.PrototypeVariable;
import org.drools.model.Query;
import org.drools.model.Variable;
import org.drools.model.impl.ModelImpl;
import org.drools.model.view.ViewItem;
import org.drools.modelcompiler.builder.KieBaseBuilder;
import org.kie.api.KieBase;
import org.kie.api.runtime.KieSession;

import static org.drools.materialized.SqlUtil.parseQuery;
import static org.drools.materialized.SqlUtil.toAlgebra;
import static org.drools.materialized.SqlUtil.toSqlSelect;
import static org.drools.model.PatternDSL.query;
import static org.drools.model.PrototypeDSL.protoPattern;
import static org.drools.model.PrototypeDSL.variable;

public class PrototypedQueryGenerator {

    private static final boolean USE_ALGEBRA = true;

    private final Map<String, Prototype> prototypes = new HashMap<>();
    private final Map<String, PrototypeVariable> variables = new HashMap<>();

    public KieSession query2KieSessionViaExecModelDSL(String query) {
         Query droolsQuery = toDroolsQuery(query);

        Model model = new ModelImpl().addQuery( droolsQuery );
        KieBase kieBase = KieBaseBuilder.createKieBaseFromModel( model );
        return kieBase.newKieSession();
    }

    private Query toDroolsQuery(String query) {
        return USE_ALGEBRA ? sqlToExecModelQuery( toAlgebra(query) ) : sqlToExecModelQuery( toSqlSelect(parseQuery(query)) );
    }

    private Query sqlToExecModelQuery(RelRoot sql) {
        return null;
    }

    private Query sqlToExecModelQuery(SqlSelect sql) {
        ViewItem[] patterns;
        if (sql.getFrom() instanceof SqlJoin) {
            patterns = processJoin( (SqlJoin) sql.getFrom() );
        } else {
            patterns = processSelect( sql );
        }

        return query( "Q0" ).build(patterns);
    }

    private ViewItem[] processSelect(SqlSelect sql) {
        SqlIdentifier sqlIdentifier = (SqlIdentifier) sql.getFrom();
        String table = sqlIdentifier.getSimple().toLowerCase();
        PrototypeDSL.PrototypePatternDef pattern = createPrototypePatternDef(table, table);
        ViewItem viewItem = pattern;

        if (sql.getGroup() != null) {
            SqlNodeList group = sql.getGroup();
            if (group.size() != 1) {
                throw new UnsupportedOperationException("group by on more than one field");
            }
            SqlIdentifier groupId = (SqlIdentifier) group.get(0);
            String groupKey = groupId.getSimple().toLowerCase();

            Variable<Object> key = DSL.declarationOf(Object.class, groupKey);
            Variable<Long> accResult = DSL.declarationOf(Long.class, "count");

            viewItem = DSL.groupBy(
                    pattern,
                    pattern.getFirstVariable(), key, p -> p.get(groupKey),
                    DSL.accFunction(CountAccumulateFunction::new).as(accResult));
        }

        return new ViewItem[] { viewItem };
    }

    private ViewItem[] processJoin(SqlJoin join) {
        PrototypeDSL.PrototypePatternDef leftPattern = toPattern( (SqlBasicCall) join.getLeft() );
        PrototypeDSL.PrototypePatternDef rightPattern = toPattern( (SqlBasicCall) join.getRight() );

        SqlBasicCall condition = (SqlBasicCall) join.getCondition();

        String leftOperand = condition.getOperands()[0].toString().toLowerCase();
        int leftDotPosition = leftOperand.indexOf('.');
        String leftField = leftOperand.substring(leftDotPosition+1).trim();
        String leftAlias = leftOperand.substring(0, leftDotPosition).trim();
        PrototypeVariable leftVar = variables.get(leftAlias);

        String rightOperand = condition.getOperands()[1].toString().toLowerCase();
        int rightDotPosition = rightOperand.indexOf('.');
        String rightField = rightOperand.substring(rightDotPosition+1).trim();

        PrototypeDSL.PrototypePatternDef firstPattern, secondPattern;
        if (leftVar == leftPattern.getFirstVariable()) {
            firstPattern = leftPattern;
            secondPattern = rightPattern;
        } else {
            firstPattern = rightPattern;
            secondPattern = leftPattern;
        }

        secondPattern.expr(rightField, Index.ConstraintType.EQUAL, leftVar, leftField);

        return new ViewItem[] { firstPattern, secondPattern };
    }

    private PrototypeDSL.PrototypePatternDef toPattern(SqlBasicCall sqlCall) {
        String table = sqlCall.getOperands()[0].toString().toLowerCase();
        String id = sqlCall.getOperands()[1].toString().toLowerCase();
        return createPrototypePatternDef(table, id);
    }

    private PrototypeDSL.PrototypePatternDef createPrototypePatternDef(String table, String id) {
        PrototypeVariable protoVar = variable(getPrototype(table), id);
        variables.put(id, protoVar);
        return protoPattern( protoVar );
    }

    public Prototype getPrototype(String name) {
        return prototypes.computeIfAbsent(name, PrototypeDSL::prototype);
    }
}
