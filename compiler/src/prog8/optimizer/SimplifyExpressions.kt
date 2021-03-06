package prog8.optimizer

import prog8.ast.Program
import prog8.ast.base.*
import prog8.ast.expressions.*
import prog8.ast.processing.IAstModifyingVisitor
import prog8.ast.statements.Assignment
import prog8.ast.statements.Statement
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow

/*
    todo add more expression optimizations

    Also see  https://egorbo.com/peephole-optimizations.html

 */

internal class SimplifyExpressions(private val program: Program) : IAstModifyingVisitor {
    var optimizationsDone: Int = 0

    override fun visit(assignment: Assignment): Statement {
        if (assignment.aug_op != null)
            throw AstException("augmented assignments should have been converted to normal assignments before this optimizer")
        return super.visit(assignment)
    }

    override fun visit(memread: DirectMemoryRead): Expression {
        // @( &thing )  -->  thing
        val addrOf = memread.addressExpression as? AddressOf
        if(addrOf!=null)
            return super.visit(addrOf.identifier)
        return super.visit(memread)
    }

    override fun visit(typecast: TypecastExpression): Expression {
        var tc = typecast

        // try to statically convert a literal value into one of the desired type
        val literal = tc.expression as? NumericLiteralValue
        if(literal!=null) {
            val newLiteral = literal.cast(tc.type)
            if(newLiteral!==literal) {
                optimizationsDone++
                return newLiteral
            }
        }

        // remove redundant typecasts
        while(true) {
            val expr = tc.expression
            if(expr !is TypecastExpression || expr.type!=tc.type) {
                val assignment = typecast.parent as? Assignment
                if(assignment!=null) {
                    val targetDt = assignment.target.inferType(program, assignment)
                    if(tc.expression.inferType(program)==targetDt) {
                        optimizationsDone++
                        return tc.expression
                    }
                }

                val subTc = tc.expression as? TypecastExpression
                if(subTc!=null) {
                    // if the previous typecast was casting to a 'bigger' type, just ignore that one
                    // if the previous typecast was casting to a similar type, ignore that one
                    if(subTc.type largerThan tc.type || subTc.type equalsSize tc.type) {
                        subTc.type = tc.type
                        subTc.parent = tc.parent
                        optimizationsDone++
                        return subTc
                    }
                }

                return super.visit(tc)
            }

            optimizationsDone++
            tc = expr
        }
    }

    override fun visit(expr: PrefixExpression): Expression {
        if (expr.operator == "+") {
            // +X --> X
            optimizationsDone++
            return expr.expression.accept(this)
        } else if (expr.operator == "not") {
            (expr.expression as? BinaryExpression)?.let {
                // NOT (...)  ->   invert  ...
                when (it.operator) {
                    "<" -> {
                        it.operator = ">="
                        optimizationsDone++
                        return it
                    }
                    ">" -> {
                        it.operator = "<="
                        optimizationsDone++
                        return it
                    }
                    "<=" -> {
                        it.operator = ">"
                        optimizationsDone++
                        return it
                    }
                    ">=" -> {
                        it.operator = "<"
                        optimizationsDone++
                        return it
                    }
                    "==" -> {
                        it.operator = "!="
                        optimizationsDone++
                        return it
                    }
                    "!=" -> {
                        it.operator = "=="
                        optimizationsDone++
                        return it
                    }
                    else -> {
                    }
                }
            }
        }
        return super.visit(expr)
    }

    override fun visit(expr: BinaryExpression): Expression {
        super.visit(expr)
        val leftVal = expr.left.constValue(program)
        val rightVal = expr.right.constValue(program)
        val constTrue = NumericLiteralValue.fromBoolean(true, expr.position)
        val constFalse = NumericLiteralValue.fromBoolean(false, expr.position)

        val leftIDt = expr.left.inferType(program)
        val rightIDt = expr.right.inferType(program)
        if(!leftIDt.isKnown || !rightIDt.isKnown)
            throw FatalAstException("can't determine datatype of both expression operands $expr")

        val leftDt = leftIDt.typeOrElse(DataType.STRUCT)
        val rightDt = rightIDt.typeOrElse(DataType.STRUCT)
        if (leftDt != rightDt) {
            // try to convert a datatype into the other (where ddd
            if (adjustDatatypes(expr, leftVal, leftDt, rightVal, rightDt)) {
                optimizationsDone++
                return expr
            }
        }

        // Value <associativeoperator> X -->  X <associativeoperator> Value
        if (leftVal != null && expr.operator in associativeOperators && rightVal == null) {
            val tmp = expr.left
            expr.left = expr.right
            expr.right = tmp
            optimizationsDone++
            return expr
        }

        // X + (-A)  -->  X - A
        if (expr.operator == "+" && (expr.right as? PrefixExpression)?.operator == "-") {
            expr.operator = "-"
            expr.right = (expr.right as PrefixExpression).expression
            optimizationsDone++
            return expr
        }

        // (-A) + X  -->  X - A
        if (expr.operator == "+" && (expr.left as? PrefixExpression)?.operator == "-") {
            expr.operator = "-"
            val newRight = (expr.left as PrefixExpression).expression
            expr.left = expr.right
            expr.right = newRight
            optimizationsDone++
            return expr
        }

        // X + (-value)  -->  X - value
        if (expr.operator == "+" && rightVal != null) {
            val rv = rightVal.number.toDouble()
            if (rv < 0.0) {
                expr.operator = "-"
                expr.right = NumericLiteralValue(rightVal.type, -rv, rightVal.position)
                optimizationsDone++
                return expr
            }
        }

        // (-value) + X  -->  X - value
        if (expr.operator == "+" && leftVal != null) {
            val lv = leftVal.number.toDouble()
            if (lv < 0.0) {
                expr.operator = "-"
                expr.right = NumericLiteralValue(leftVal.type, -lv, leftVal.position)
                optimizationsDone++
                return expr
            }
        }

        // X - (-A)  -->  X + A
        if (expr.operator == "-" && (expr.right as? PrefixExpression)?.operator == "-") {
            expr.operator = "+"
            expr.right = (expr.right as PrefixExpression).expression
            optimizationsDone++
            return expr
        }

        // X - (-value)  -->  X + value
        if (expr.operator == "-" && rightVal != null) {
            val rv = rightVal.number.toDouble()
            if (rv < 0.0) {
                expr.operator = "+"
                expr.right = NumericLiteralValue(rightVal.type, -rv, rightVal.position)
                optimizationsDone++
                return expr
            }
        }

        if (expr.operator == "+" || expr.operator == "-"
                && leftVal == null && rightVal == null
                && leftDt in NumericDatatypes && rightDt in NumericDatatypes) {
            val leftBinExpr = expr.left as? BinaryExpression
            val rightBinExpr = expr.right as? BinaryExpression
            if (leftBinExpr?.operator == "*") {
                if (expr.operator == "+") {
                    // Y*X + X  ->  X*(Y - 1)
                    // X*Y + X  ->  X*(Y - 1)
                    val x = expr.right
                    val y = determineY(x, leftBinExpr)
                    if(y!=null) {
                        val yPlus1 = BinaryExpression(y, "+", NumericLiteralValue(leftDt, 1, y.position), y.position)
                        return BinaryExpression(x, "*", yPlus1, x.position)
                    }
                } else {
                    // Y*X - X  ->  X*(Y - 1)
                    // X*Y - X  ->  X*(Y - 1)
                    val x = expr.right
                    val y = determineY(x, leftBinExpr)
                    if(y!=null) {
                        val yMinus1 = BinaryExpression(y, "-", NumericLiteralValue(leftDt, 1, y.position), y.position)
                        return BinaryExpression(x, "*", yMinus1, x.position)
                    }
                }
            }
            else if(rightBinExpr?.operator=="*") {
                if(expr.operator=="+") {
                    // X + Y*X  ->  X*(Y + 1)
                    // X + X*Y  ->  X*(Y + 1)
                    val x = expr.left
                    val y = determineY(x, rightBinExpr)
                    if(y!=null) {
                        val yPlus1 = BinaryExpression(y, "+", NumericLiteralValue.optimalInteger(1, y.position), y.position)
                        return BinaryExpression(x, "*", yPlus1, x.position)
                    }
                } else {
                    // X - Y*X  ->  X*(1 - Y)
                    // X - X*Y  ->  X*(1 - Y)
                    val x = expr.left
                    val y = determineY(x, rightBinExpr)
                    if(y!=null) {
                        val oneMinusY = BinaryExpression(NumericLiteralValue.optimalInteger(1, y.position), "-", y, y.position)
                        return BinaryExpression(x, "*", oneMinusY, x.position)
                    }
                }
            }
        }


        // simplify when a term is constant and determines the outcome
        when (expr.operator) {
            "or" -> {
                if ((leftVal != null && leftVal.asBooleanValue) || (rightVal != null && rightVal.asBooleanValue)) {
                    optimizationsDone++
                    return constTrue
                }
                if (leftVal != null && !leftVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.right
                }
                if (rightVal != null && !rightVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.left
                }
            }
            "and" -> {
                if ((leftVal != null && !leftVal.asBooleanValue) || (rightVal != null && !rightVal.asBooleanValue)) {
                    optimizationsDone++
                    return constFalse
                }
                if (leftVal != null && leftVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.right
                }
                if (rightVal != null && rightVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.left
                }
            }
            "xor" -> {
                if (leftVal != null && !leftVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.right
                }
                if (rightVal != null && !rightVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.left
                }
                if (leftVal != null && leftVal.asBooleanValue) {
                    optimizationsDone++
                    return PrefixExpression("not", expr.right, expr.right.position)
                }
                if (rightVal != null && rightVal.asBooleanValue) {
                    optimizationsDone++
                    return PrefixExpression("not", expr.left, expr.left.position)
                }
            }
            "|", "^" -> {
                if (leftVal != null && !leftVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.right
                }
                if (rightVal != null && !rightVal.asBooleanValue) {
                    optimizationsDone++
                    return expr.left
                }
            }
            "&" -> {
                if (leftVal != null && !leftVal.asBooleanValue) {
                    optimizationsDone++
                    return constFalse
                }
                if (rightVal != null && !rightVal.asBooleanValue) {
                    optimizationsDone++
                    return constFalse
                }
            }
            "*" -> return optimizeMultiplication(expr, leftVal, rightVal)
            "/" -> return optimizeDivision(expr, leftVal, rightVal)
            "+" -> return optimizeAdd(expr, leftVal, rightVal)
            "-" -> return optimizeSub(expr, leftVal, rightVal)
            "**" -> return optimizePower(expr, leftVal, rightVal)
            "%" -> return optimizeRemainder(expr, leftVal, rightVal)
            ">>" -> return optimizeShiftRight(expr, rightVal)
            "<<" -> return optimizeShiftLeft(expr, rightVal)
        }
        return expr
    }

    private fun determineY(x: Expression, subBinExpr: BinaryExpression): Expression? {
        return when {
            subBinExpr.left isSameAs x -> subBinExpr.right
            subBinExpr.right isSameAs x -> subBinExpr.left
            else -> null
        }
    }

    private fun adjustDatatypes(expr: BinaryExpression,
                                leftConstVal: NumericLiteralValue?, leftDt: DataType,
                                rightConstVal: NumericLiteralValue?, rightDt: DataType): Boolean {

        fun adjust(value: NumericLiteralValue, targetDt: DataType): Pair<Boolean, NumericLiteralValue>{
            if(value.type==targetDt)
                return Pair(false, value)
            when(value.type) {
                DataType.UBYTE -> {
                    if (targetDt == DataType.BYTE) {
                        if(value.number.toInt() < 127)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toShort(), value.position))
                    }
                    else if (targetDt == DataType.UWORD || targetDt == DataType.WORD)
                        return Pair(true, NumericLiteralValue(targetDt, value.number.toInt(), value.position))
                }
                DataType.BYTE -> {
                    if (targetDt == DataType.UBYTE) {
                        if(value.number.toInt() >= 0)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toInt(), value.position))
                    }
                    else if (targetDt == DataType.UWORD) {
                        if(value.number.toInt() >= 0)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toInt(), value.position))
                    }
                    else if (targetDt == DataType.WORD) return Pair(true, NumericLiteralValue(targetDt, value.number.toInt(), value.position))
                }
                DataType.UWORD -> {
                    if (targetDt == DataType.UBYTE) {
                        if(value.number.toInt() <= 255)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toShort(), value.position))
                    }
                    else if (targetDt == DataType.BYTE) {
                        if(value.number.toInt() <= 127)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toShort(), value.position))
                    }
                    else if (targetDt == DataType.WORD) {
                        if(value.number.toInt() <= 32767)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toInt(), value.position))
                    }
                }
                DataType.WORD -> {
                    if (targetDt == DataType.UBYTE) {
                        if(value.number.toInt() in 0..255)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toShort(), value.position))
                    }
                    else if (targetDt == DataType.BYTE) {
                        if(value.number.toInt() in -128..127)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toShort(), value.position))
                    }
                    else if (targetDt == DataType.UWORD) {
                        if(value.number.toInt() >= 0)
                            return Pair(true, NumericLiteralValue(targetDt, value.number.toShort(), value.position))
                    }
                }
                else -> {}
            }
            return Pair(false, value)
        }

        if(leftConstVal==null && rightConstVal!=null) {
            if(leftDt largerThan rightDt) {
                val (adjusted, newValue) = adjust(rightConstVal, leftDt)
                if (adjusted) {
                    expr.right = newValue
                    optimizationsDone++
                    return true
                }
            }
            return false
        } else if(leftConstVal!=null && rightConstVal==null) {
            if(rightDt largerThan leftDt) {
                val (adjusted, newValue) = adjust(leftConstVal, rightDt)
                if (adjusted) {
                    expr.left = newValue
                    optimizationsDone++
                    return true
                }
            }
            return false
        } else {
            return false    // two const values, don't adjust (should have been const-folded away)
        }
    }

    private data class ReorderedAssociativeBinaryExpr(val expr: BinaryExpression, val leftVal: NumericLiteralValue?, val rightVal: NumericLiteralValue?)

    private fun reorderAssociative(expr: BinaryExpression, leftVal: NumericLiteralValue?): ReorderedAssociativeBinaryExpr {
        if(expr.operator in associativeOperators && leftVal!=null) {
            // swap left and right so that right is always the constant
            val tmp = expr.left
            expr.left = expr.right
            expr.right = tmp
            optimizationsDone++
            return ReorderedAssociativeBinaryExpr(expr, expr.right.constValue(program), leftVal)
        }
        return ReorderedAssociativeBinaryExpr(expr, leftVal, expr.right.constValue(program))
    }

    private fun optimizeAdd(pexpr: BinaryExpression, pleftVal: NumericLiteralValue?, prightVal: NumericLiteralValue?): Expression {
        if(pleftVal==null && prightVal==null)
            return pexpr

        val (expr, _, rightVal) = reorderAssociative(pexpr, pleftVal)
        if(rightVal!=null) {
            // right value is a constant, see if we can optimize
            val rightConst: NumericLiteralValue = rightVal
            when(rightConst.number.toDouble()) {
                0.0 -> {
                    // left
                    optimizationsDone++
                    return expr.left
                }
            }
        }
        // no need to check for left val constant (because of associativity)

        return expr
    }

    private fun optimizeSub(expr: BinaryExpression, leftVal: NumericLiteralValue?, rightVal: NumericLiteralValue?): Expression {
        if(leftVal==null && rightVal==null)
            return expr

        if(rightVal!=null) {
            // right value is a constant, see if we can optimize
            val rightConst: NumericLiteralValue = rightVal
            when(rightConst.number.toDouble()) {
                0.0 -> {
                    // left
                    optimizationsDone++
                    return expr.left
                }
            }
        }
        if(leftVal!=null) {
            // left value is a constant, see if we can optimize
            when(leftVal.number.toDouble()) {
                0.0 -> {
                    // -right
                    optimizationsDone++
                    return PrefixExpression("-", expr.right, expr.position)
                }
            }
        }

        return expr
    }

    private fun optimizePower(expr: BinaryExpression, leftVal: NumericLiteralValue?, rightVal: NumericLiteralValue?): Expression {
        if(leftVal==null && rightVal==null)
            return expr

        if(rightVal!=null) {
            // right value is a constant, see if we can optimize
            val rightConst: NumericLiteralValue = rightVal
            when(rightConst.number.toDouble()) {
                -3.0 -> {
                    // -1/(left*left*left)
                    optimizationsDone++
                    return BinaryExpression(NumericLiteralValue(DataType.FLOAT, -1.0, expr.position), "/",
                            BinaryExpression(expr.left, "*", BinaryExpression(expr.left, "*", expr.left, expr.position), expr.position),
                            expr.position)
                }
                -2.0 -> {
                    // -1/(left*left)
                    optimizationsDone++
                    return BinaryExpression(NumericLiteralValue(DataType.FLOAT, -1.0, expr.position), "/",
                            BinaryExpression(expr.left, "*", expr.left, expr.position),
                            expr.position)
                }
                -1.0 -> {
                    // -1/left
                    optimizationsDone++
                    return BinaryExpression(NumericLiteralValue(DataType.FLOAT, -1.0, expr.position), "/",
                            expr.left, expr.position)
                }
                0.0 -> {
                    // 1
                    optimizationsDone++
                    return NumericLiteralValue(rightConst.type, 1, expr.position)
                }
                0.5 -> {
                    // sqrt(left)
                    optimizationsDone++
                    return FunctionCall(IdentifierReference(listOf("sqrt"), expr.position), mutableListOf(expr.left), expr.position)
                }
                1.0 -> {
                    // left
                    optimizationsDone++
                    return expr.left
                }
                2.0 -> {
                    // left*left
                    optimizationsDone++
                    return BinaryExpression(expr.left, "*", expr.left, expr.position)
                }
                3.0 -> {
                    // left*left*left
                    optimizationsDone++
                    return BinaryExpression(expr.left, "*", BinaryExpression(expr.left, "*", expr.left, expr.position), expr.position)
                }
            }
        }
        if(leftVal!=null) {
            // left value is a constant, see if we can optimize
            when(leftVal.number.toDouble()) {
                -1.0 -> {
                    // -1
                    optimizationsDone++
                    return NumericLiteralValue(DataType.FLOAT, -1.0, expr.position)
                }
                0.0 -> {
                    // 0
                    optimizationsDone++
                    return NumericLiteralValue(leftVal.type, 0, expr.position)
                }
                1.0 -> {
                    //1
                    optimizationsDone++
                    return NumericLiteralValue(leftVal.type, 1, expr.position)
                }

            }
        }

        return expr
    }

    private fun optimizeRemainder(expr: BinaryExpression, leftVal: NumericLiteralValue?, rightVal: NumericLiteralValue?): Expression {
        if(leftVal==null && rightVal==null)
            return expr

        // simplify assignments  A = B <operator> C

        val cv = rightVal?.number?.toInt()?.toDouble()
        when(expr.operator) {
            "%" -> {
                if (cv == 1.0) {
                    optimizationsDone++
                    return NumericLiteralValue(expr.inferType(program).typeOrElse(DataType.STRUCT), 0, expr.position)
                } else if (cv == 2.0) {
                    optimizationsDone++
                    expr.operator = "&"
                    expr.right = NumericLiteralValue.optimalInteger(1, expr.position)
                    return expr
                }
            }
        }
        return expr

    }

    private val powersOfTwo = (1 .. 16).map { (2.0).pow(it) }.toSet()
    private val negativePowersOfTwo = powersOfTwo.map { -it }.toSet()

    private fun optimizeDivision(expr: BinaryExpression, leftVal: NumericLiteralValue?, rightVal: NumericLiteralValue?): Expression {
        if(leftVal==null && rightVal==null)
            return expr

        // cannot shuffle assiciativity with division!
        if(rightVal!=null) {
            // right value is a constant, see if we can optimize
            val rightConst: NumericLiteralValue = rightVal
            val cv = rightConst.number.toDouble()
            val leftIDt = expr.left.inferType(program)
            if(!leftIDt.isKnown)
                return expr
            val leftDt = leftIDt.typeOrElse(DataType.STRUCT)
            when(cv) {
                -1.0 -> {
                    //  '/' -> -left
                    if (expr.operator == "/") {
                        optimizationsDone++
                        return PrefixExpression("-", expr.left, expr.position)
                    }
                }
                1.0 -> {
                    //  '/' -> left
                    if (expr.operator == "/") {
                        optimizationsDone++
                        return expr.left
                    }
                }
                in powersOfTwo -> {
                    if(leftDt in IntegerDatatypes) {
                        // divided by a power of two => shift right
                        optimizationsDone++
                        val numshifts = log2(cv).toInt()
                        return BinaryExpression(expr.left, ">>", NumericLiteralValue.optimalInteger(numshifts, expr.position), expr.position)
                    }
                }
                in negativePowersOfTwo -> {
                    if(leftDt in IntegerDatatypes) {
                        // divided by a negative power of two => negate, then shift right
                        optimizationsDone++
                        val numshifts = log2(-cv).toInt()
                        return BinaryExpression(PrefixExpression("-", expr.left, expr.position), ">>", NumericLiteralValue.optimalInteger(numshifts, expr.position), expr.position)
                    }
                }
            }

            if (leftDt == DataType.UBYTE) {
                if(abs(rightConst.number.toDouble()) >= 256.0) {
                    optimizationsDone++
                    return NumericLiteralValue(DataType.UBYTE, 0, expr.position)
                }
            }
            else if (leftDt == DataType.UWORD) {
                if(abs(rightConst.number.toDouble()) >= 65536.0) {
                    optimizationsDone++
                    return NumericLiteralValue(DataType.UBYTE, 0, expr.position)
                }
            }
        }

        if(leftVal!=null) {
            // left value is a constant, see if we can optimize
            when(leftVal.number.toDouble()) {
                0.0 -> {
                    // 0
                    optimizationsDone++
                    return NumericLiteralValue(leftVal.type, 0, expr.position)
                }
            }
        }

        return expr
    }

    private fun optimizeMultiplication(pexpr: BinaryExpression, pleftVal: NumericLiteralValue?, prightVal: NumericLiteralValue?): Expression {
        if(pleftVal==null && prightVal==null)
            return pexpr

        val (expr, _, rightVal) = reorderAssociative(pexpr, pleftVal)
        if(rightVal!=null) {
            // right value is a constant, see if we can optimize
            val leftValue: Expression = expr.left
            val rightConst: NumericLiteralValue = rightVal
            when(val cv = rightConst.number.toDouble()) {
                -1.0 -> {
                    // -left
                    optimizationsDone++
                    return PrefixExpression("-", leftValue, expr.position)
                }
                0.0 -> {
                    // 0
                    optimizationsDone++
                    return NumericLiteralValue(rightConst.type, 0, expr.position)
                }
                1.0 -> {
                    // left
                    optimizationsDone++
                    return expr.left
                }
                in powersOfTwo -> {
                    if(leftValue.inferType(program).typeOrElse(DataType.STRUCT) in IntegerDatatypes) {
                        // times a power of two => shift left
                        optimizationsDone++
                        val numshifts = log2(cv).toInt()
                        return BinaryExpression(expr.left, "<<", NumericLiteralValue.optimalInteger(numshifts, expr.position), expr.position)
                    }
                }
                in negativePowersOfTwo -> {
                    if(leftValue.inferType(program).typeOrElse(DataType.STRUCT) in IntegerDatatypes) {
                        // times a negative power of two => negate, then shift left
                        optimizationsDone++
                        val numshifts = log2(-cv).toInt()
                        return BinaryExpression(PrefixExpression("-", expr.left, expr.position), "<<", NumericLiteralValue.optimalInteger(numshifts, expr.position), expr.position)
                    }
                }
            }
        }
        // no need to check for left val constant (because of associativity)

        return expr
    }

    private fun optimizeShiftLeft(expr: BinaryExpression, amountLv: NumericLiteralValue?): Expression {
        if(amountLv==null)
            return expr

        val amount=amountLv.number.toInt()
        if(amount==0) {
            optimizationsDone++
            return expr.left
        }
        val targetDt = expr.left.inferType(program).typeOrElse(DataType.STRUCT)
        when(targetDt) {
            DataType.UBYTE, DataType.BYTE -> {
                if(amount>=8) {
                    optimizationsDone++
                    return NumericLiteralValue.optimalInteger(0, expr.position)
                }
            }
            DataType.UWORD, DataType.WORD -> {
                if(amount>=16) {
                    optimizationsDone++
                    return NumericLiteralValue.optimalInteger(0, expr.position)
                }
                else if(amount>=8) {
                    optimizationsDone++
                    val lsb=TypecastExpression(expr.left, DataType.UBYTE, true, expr.position)
                    if(amount==8) {
                        return FunctionCall(IdentifierReference(listOf("mkword"), expr.position), mutableListOf(NumericLiteralValue.optimalInteger(0, expr.position), lsb), expr.position)
                    }
                    val shifted = BinaryExpression(lsb, "<<", NumericLiteralValue.optimalInteger(amount-8, expr.position), expr.position)
                    return FunctionCall(IdentifierReference(listOf("mkword"), expr.position), mutableListOf(NumericLiteralValue.optimalInteger(0, expr.position), shifted), expr.position)
                }
            }
            else -> {}
        }
        return expr
    }

    private fun optimizeShiftRight(expr: BinaryExpression, amountLv: NumericLiteralValue?): Expression {
        if(amountLv==null)
            return expr

        val amount=amountLv.number.toInt()
        if(amount==0) {
            optimizationsDone++
            return expr.left
        }
        val targetDt = expr.left.inferType(program).typeOrElse(DataType.STRUCT)
        when(targetDt) {
            DataType.UBYTE -> {
                if(amount>=8) {
                    optimizationsDone++
                    return NumericLiteralValue.optimalInteger(0, expr.position)
                }
            }
            DataType.BYTE -> {
                if(amount>8) {
                    expr.right = NumericLiteralValue.optimalInteger(8, expr.right.position)
                    return expr
                }
            }
            DataType.UWORD -> {
                if(amount>=16) {
                    optimizationsDone++
                    return NumericLiteralValue.optimalInteger(0, expr.position)
                }
                else if(amount>=8) {
                    optimizationsDone++
                    val msb=FunctionCall(IdentifierReference(listOf("msb"), expr.position), mutableListOf(expr.left), expr.position)
                    if(amount==8)
                        return msb
                    return BinaryExpression(msb, ">>", NumericLiteralValue.optimalInteger(amount-8, expr.position), expr.position)
                }
            }
            DataType.WORD -> {
                if(amount>16) {
                    expr.right = NumericLiteralValue.optimalInteger(16, expr.right.position)
                    return expr
                } else if(amount>=8) {
                    optimizationsDone++
                    val msbAsByte = TypecastExpression(
                            FunctionCall(IdentifierReference(listOf("msb"), expr.position), mutableListOf(expr.left), expr.position),
                            DataType.BYTE,
                            true, expr.position)
                    if(amount==8)
                        return msbAsByte
                    return BinaryExpression(msbAsByte, ">>", NumericLiteralValue.optimalInteger(amount-8, expr.position), expr.position)
                }
            }
            else -> {}
        }
        return expr
    }
}
