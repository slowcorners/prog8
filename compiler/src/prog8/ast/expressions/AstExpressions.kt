package prog8.ast.expressions

import prog8.ast.*
import prog8.ast.antlr.escape
import prog8.ast.base.*
import prog8.ast.processing.IAstModifyingVisitor
import prog8.ast.processing.IAstVisitor
import prog8.ast.statements.ArrayIndex
import prog8.ast.statements.BuiltinFunctionStatementPlaceholder
import prog8.ast.statements.Subroutine
import prog8.ast.statements.VarDecl
import prog8.compiler.target.CompilationTarget
import prog8.functions.BuiltinFunctions
import prog8.functions.NotConstArgumentException
import prog8.functions.builtinFunctionReturnType
import java.util.Objects
import kotlin.math.abs


val associativeOperators = setOf("+", "*", "&", "|", "^", "or", "and", "xor", "==", "!=")


sealed class Expression: Node {
    abstract fun constValue(program: Program): NumericLiteralValue?
    abstract fun accept(visitor: IAstModifyingVisitor): Expression
    abstract fun accept(visitor: IAstVisitor)
    abstract fun referencesIdentifiers(vararg name: String): Boolean     // todo: remove this and add identifier usage tracking into CallGraph instead
    abstract fun inferType(program: Program): InferredTypes.InferredType

    infix fun isSameAs(other: Expression): Boolean {
        if(this===other)
            return true
        when(this) {
            is RegisterExpr ->
                return (other is RegisterExpr && other.register==register)
            is IdentifierReference ->
                return (other is IdentifierReference && other.nameInSource==nameInSource)
            is PrefixExpression ->
                return (other is PrefixExpression && other.operator==operator && other.expression isSameAs expression)
            is BinaryExpression ->
                return (other is BinaryExpression && other.operator==operator
                        && other.left isSameAs left
                        && other.right isSameAs right)
            is ArrayIndexedExpression -> {
                return (other is ArrayIndexedExpression && other.identifier.nameInSource == identifier.nameInSource
                        && other.arrayspec.index isSameAs arrayspec.index)
            }
            else -> return other==this
        }
    }
}


class PrefixExpression(val operator: String, var expression: Expression, override val position: Position) : Expression() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        expression.linkParents(this)
    }

    override fun constValue(program: Program): NumericLiteralValue? = null
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String) = expression.referencesIdentifiers(*name)
    override fun inferType(program: Program): InferredTypes.InferredType = expression.inferType(program)

    override fun toString(): String {
        return "Prefix($operator $expression)"
    }
}

class BinaryExpression(var left: Expression, var operator: String, var right: Expression, override val position: Position) : Expression() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        left.linkParents(this)
        right.linkParents(this)
    }

    override fun toString(): String {
        return "[$left $operator $right]"
    }

    // binary expression should actually have been optimized away into a single value, before const value was requested...
    override fun constValue(program: Program): NumericLiteralValue? = null

    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String) = left.referencesIdentifiers(*name) || right.referencesIdentifiers(*name)
    override fun inferType(program: Program): InferredTypes.InferredType {
        val leftDt = left.inferType(program)
        val rightDt = right.inferType(program)
        return when (operator) {
            "+", "-", "*", "**", "%", "/" -> {
                if (!leftDt.isKnown || !rightDt.isKnown)
                    InferredTypes.unknown()
                else {
                    try {
                        InferredTypes.knownFor(commonDatatype(
                                leftDt.typeOrElse(DataType.BYTE),
                                rightDt.typeOrElse(DataType.BYTE),
                                null, null).first)
                    } catch (x: FatalAstException) {
                        InferredTypes.unknown()
                    }
                }
            }
            "&" -> leftDt
            "|" -> leftDt
            "^" -> leftDt
            "and", "or", "xor",
            "<", ">",
            "<=", ">=",
            "==", "!=" -> InferredTypes.knownFor(DataType.UBYTE)
            "<<", ">>" -> leftDt
            else -> throw FatalAstException("resulting datatype check for invalid operator $operator")
        }
    }

    companion object {
        fun commonDatatype(leftDt: DataType, rightDt: DataType,
                           left: Expression?, right: Expression?): Pair<DataType, Expression?> {
            // byte + byte -> byte
            // byte + word -> word
            // word + byte -> word
            // word + word -> word
            // a combination with a float will be float (but give a warning about this!)

            return when (leftDt) {
                DataType.UBYTE -> {
                    when (rightDt) {
                        DataType.UBYTE -> Pair(DataType.UBYTE, null)
                        DataType.BYTE -> Pair(DataType.BYTE, left)
                        DataType.UWORD -> Pair(DataType.UWORD, left)
                        DataType.WORD -> Pair(DataType.WORD, left)
                        DataType.FLOAT -> Pair(DataType.FLOAT, left)
                        else -> Pair(leftDt, null)      // non-numeric datatype
                    }
                }
                DataType.BYTE -> {
                    when (rightDt) {
                        DataType.UBYTE -> Pair(DataType.BYTE, right)
                        DataType.BYTE -> Pair(DataType.BYTE, null)
                        DataType.UWORD -> Pair(DataType.WORD, left)
                        DataType.WORD -> Pair(DataType.WORD, left)
                        DataType.FLOAT -> Pair(DataType.FLOAT, left)
                        else -> Pair(leftDt, null)      // non-numeric datatype
                    }
                }
                DataType.UWORD -> {
                    when (rightDt) {
                        DataType.UBYTE -> Pair(DataType.UWORD, right)
                        DataType.BYTE -> Pair(DataType.WORD, right)
                        DataType.UWORD -> Pair(DataType.UWORD, null)
                        DataType.WORD -> Pair(DataType.WORD, left)
                        DataType.FLOAT -> Pair(DataType.FLOAT, left)
                        else -> Pair(leftDt, null)      // non-numeric datatype
                    }
                }
                DataType.WORD -> {
                    when (rightDt) {
                        DataType.UBYTE -> Pair(DataType.WORD, right)
                        DataType.BYTE -> Pair(DataType.WORD, right)
                        DataType.UWORD -> Pair(DataType.WORD, right)
                        DataType.WORD -> Pair(DataType.WORD, null)
                        DataType.FLOAT -> Pair(DataType.FLOAT, left)
                        else -> Pair(leftDt, null)      // non-numeric datatype
                    }
                }
                DataType.FLOAT -> {
                    Pair(DataType.FLOAT, right)
                }
                else -> Pair(leftDt, null)      // non-numeric datatype
            }
        }
    }
}

class ArrayIndexedExpression(var identifier: IdentifierReference,
                             val arrayspec: ArrayIndex,
                             override val position: Position) : Expression(), IAssignable {
    override lateinit var parent: Node
    override fun linkParents(parent: Node) {
        this.parent = parent
        identifier.linkParents(this)
        arrayspec.linkParents(this)
    }

    override fun constValue(program: Program): NumericLiteralValue? = null
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String) = identifier.referencesIdentifiers(*name)

    override fun inferType(program: Program): InferredTypes.InferredType {
        val target = identifier.targetStatement(program.namespace)
        if (target is VarDecl) {
            return when (target.datatype) {
                in StringDatatypes -> InferredTypes.knownFor(DataType.UBYTE)
                in ArrayDatatypes -> InferredTypes.knownFor(ArrayElementTypes.getValue(target.datatype))
                else -> InferredTypes.unknown()
            }
        }
        return InferredTypes.unknown()
    }

    override fun toString(): String {
        return "ArrayIndexed(ident=$identifier, arraysize=$arrayspec; pos=$position)"
    }
}

class TypecastExpression(var expression: Expression, var type: DataType, val implicit: Boolean, override val position: Position) : Expression() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        expression.linkParents(this)
    }

    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String) = expression.referencesIdentifiers(*name)
    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(type)
    override fun constValue(program: Program): NumericLiteralValue? {
        val cv = expression.constValue(program) ?: return null
        return cv.cast(type)
        // val value = RuntimeValue(cv.type, cv.asNumericValue!!).cast(type)
        // return LiteralValue.fromNumber(value.numericValue(), value.type, position).cast(type)
    }

    override fun toString(): String {
        return "Typecast($expression as $type)"
    }
}

data class AddressOf(var identifier: IdentifierReference, override val position: Position) : Expression() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        identifier.parent=this
    }

    override fun constValue(program: Program): NumericLiteralValue? = null
    override fun referencesIdentifiers(vararg name: String) = false
    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(DataType.UWORD)
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
}

class DirectMemoryRead(var addressExpression: Expression, override val position: Position) : Expression(), IAssignable {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        this.addressExpression.linkParents(this)
    }

    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String) = false
    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(DataType.UBYTE)
    override fun constValue(program: Program): NumericLiteralValue? = null

    override fun toString(): String {
        return "DirectMemoryRead($addressExpression)"
    }
}

class NumericLiteralValue(val type: DataType,    // only numerical types allowed
                          val number: Number,    // can be byte, word or float depending on the type
                          override val position: Position) : Expression() {
    override lateinit var parent: Node

    companion object {
        fun fromBoolean(bool: Boolean, position: Position) =
                NumericLiteralValue(DataType.UBYTE, if (bool) 1 else 0, position)

        fun optimalNumeric(value: Number, position: Position): NumericLiteralValue {
            return if(value is Double) {
                NumericLiteralValue(DataType.FLOAT, value, position)
            } else {
                when (val intval = value.toInt()) {
                    in 0..255 -> NumericLiteralValue(DataType.UBYTE, intval, position)
                    in -128..127 -> NumericLiteralValue(DataType.BYTE, intval, position)
                    in 0..65535 -> NumericLiteralValue(DataType.UWORD, intval, position)
                    in -32768..32767 -> NumericLiteralValue(DataType.WORD, intval, position)
                    else -> NumericLiteralValue(DataType.FLOAT, intval.toDouble(), position)
                }
            }
        }

        fun optimalInteger(value: Int, position: Position): NumericLiteralValue {
            return when (value) {
                in 0..255 -> NumericLiteralValue(DataType.UBYTE, value, position)
                in -128..127 -> NumericLiteralValue(DataType.BYTE, value, position)
                in 0..65535 -> NumericLiteralValue(DataType.UWORD, value, position)
                in -32768..32767 -> NumericLiteralValue(DataType.WORD, value, position)
                else -> throw FatalAstException("integer overflow: $value")
            }
        }
    }

    val asBooleanValue: Boolean = number!=0

    override fun linkParents(parent: Node) {
        this.parent = parent
    }

    override fun referencesIdentifiers(vararg name: String) = false
    override fun constValue(program: Program) = this

    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)

    override fun toString(): String = "NumericLiteral(${type.name}:$number)"

    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(type)

    override fun hashCode(): Int = Objects.hash(type, number)

    override fun equals(other: Any?): Boolean {
        if(other==null || other !is NumericLiteralValue)
            return false
        return number.toDouble()==other.number.toDouble()
    }

    operator fun compareTo(other: NumericLiteralValue): Int = number.toDouble().compareTo(other.number.toDouble())

    fun cast(targettype: DataType): NumericLiteralValue {
        if(type==targettype)
            return this
        val numval = number.toDouble()
        when(type) {
            DataType.UBYTE -> {
                if(targettype== DataType.BYTE && numval <= 127)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if(targettype== DataType.WORD || targettype== DataType.UWORD)
                    return NumericLiteralValue(targettype, number.toInt(), position)
                if(targettype== DataType.FLOAT)
                    return NumericLiteralValue(targettype, number.toDouble(), position)
            }
            DataType.BYTE -> {
                if(targettype== DataType.UBYTE && numval >= 0)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if(targettype== DataType.UWORD && numval >= 0)
                    return NumericLiteralValue(targettype, number.toInt(), position)
                if(targettype== DataType.WORD)
                    return NumericLiteralValue(targettype, number.toInt(), position)
                if(targettype== DataType.FLOAT)
                    return NumericLiteralValue(targettype, number.toDouble(), position)
            }
            DataType.UWORD -> {
                if(targettype== DataType.BYTE && numval <= 127)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if(targettype== DataType.UBYTE && numval <= 255)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if(targettype== DataType.WORD && numval <= 32767)
                    return NumericLiteralValue(targettype, number.toInt(), position)
                if(targettype== DataType.FLOAT)
                    return NumericLiteralValue(targettype, number.toDouble(), position)
            }
            DataType.WORD -> {
                if(targettype== DataType.BYTE && numval >= -128 && numval <=127)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if(targettype== DataType.UBYTE && numval >= 0 && numval <= 255)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if(targettype== DataType.UWORD && numval >=0)
                    return NumericLiteralValue(targettype, number.toInt(), position)
                if(targettype== DataType.FLOAT)
                    return NumericLiteralValue(targettype, number.toDouble(), position)
            }
            DataType.FLOAT -> {
                if (targettype == DataType.BYTE && numval >= -128 && numval <=127)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if (targettype == DataType.UBYTE && numval >=0 && numval <= 255)
                    return NumericLiteralValue(targettype, number.toShort(), position)
                if (targettype == DataType.WORD && numval >= -32768 && numval <= 32767)
                    return NumericLiteralValue(targettype, number.toInt(), position)
                if (targettype == DataType.UWORD && numval >=0 && numval <= 65535)
                    return NumericLiteralValue(targettype, number.toInt(), position)
            }
            else -> {}
        }
        throw ExpressionError("can't cast $type into $targettype", position)
    }
}

class StructLiteralValue(var values: List<Expression>,
                         override val position: Position): Expression() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent=parent
        values.forEach { it.linkParents(this) }
    }

    override fun constValue(program: Program): NumericLiteralValue?  = null
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String) = values.any { it.referencesIdentifiers(*name) }
    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(DataType.STRUCT)

    override fun toString(): String {
        return "struct{ ${values.joinToString(", ")} }"
    }
}

class StringLiteralValue(val type: DataType,     // only string types
                         val value: String,
                         override val position: Position) : Expression() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
    }
    override fun referencesIdentifiers(vararg name: String) = false
    override fun constValue(program: Program): NumericLiteralValue? = null
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun toString(): String = "'${escape(value)}'"
    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(type)
    operator fun compareTo(other: StringLiteralValue): Int = value.compareTo(other.value)
    override fun hashCode(): Int = Objects.hash(value, type)
    override fun equals(other: Any?): Boolean {
        if(other==null || other !is StringLiteralValue)
            return false
        return value==other.value && type==other.type
    }

    var heapId: Int? = null
        private set

    fun addToHeap() {
        if(heapId==null)
            heapId = ++heapIdSequence
    }
}

private var heapIdSequence = 0


class ArrayLiteralValue(val type: DataType,     // only array types
                        val value: Array<Expression>,
                        initHeapId: Int? =null,
                        override val position: Position) : Expression() {
    override lateinit var parent: Node

    var heapId = initHeapId
        private set

    override fun linkParents(parent: Node) {
        this.parent = parent
        value.forEach {it.linkParents(this)}
    }
    override fun referencesIdentifiers(vararg name: String) = value.any { it.referencesIdentifiers(*name) }
    override fun constValue(program: Program): NumericLiteralValue? = null
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun toString(): String = "$value"
    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(type)
    operator fun compareTo(other: ArrayLiteralValue): Int = throw ExpressionError("cannot order compare arrays", position)
    override fun hashCode(): Int = Objects.hash(value, type)
    override fun equals(other: Any?): Boolean {
        if(other==null || other !is ArrayLiteralValue)
            return false
        return type==other.type && value.contentEquals(other.value)
    }

    fun cast(targettype: DataType): ArrayLiteralValue? {
        if(type==targettype)
            return this
        if(targettype in ArrayDatatypes) {
            val elementType = ArrayElementTypes.getValue(targettype)
            val castArray = value.map{
                val num = it as? NumericLiteralValue
                if(num==null) {
                    // an array of UWORDs could possibly also contain AddressOfs
                    if (elementType != DataType.UWORD || it !is AddressOf)
                        throw FatalAstException("weird array element $it")
                    it
                } else {
                    try {
                        num.cast(elementType)
                    } catch(x: ExpressionError) {
                        return null
                    }
                }
            }.toTypedArray()
            return ArrayLiteralValue(targettype, castArray, position = position)
        }
        return null    // invalid type conversion from $this to $targettype
    }

    fun addToHeap() {
        if(heapId==null)
            heapId = ++heapIdSequence
    }
}

class RangeExpr(var from: Expression,
                var to: Expression,
                var step: Expression,
                override val position: Position) : Expression() {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        from.linkParents(this)
        to.linkParents(this)
        step.linkParents(this)
    }

    override fun constValue(program: Program): NumericLiteralValue? = null
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String): Boolean  = from.referencesIdentifiers(*name) || to.referencesIdentifiers(*name)
    override fun inferType(program: Program): InferredTypes.InferredType {
        val fromDt=from.inferType(program)
        val toDt=to.inferType(program)
        return when {
            !fromDt.isKnown || !toDt.isKnown -> InferredTypes.unknown()
            fromDt istype DataType.UBYTE && toDt istype DataType.UBYTE -> InferredTypes.knownFor(DataType.ARRAY_UB)
            fromDt istype DataType.UWORD && toDt istype DataType.UWORD -> InferredTypes.knownFor(DataType.ARRAY_UW)
            fromDt istype DataType.STR && toDt istype DataType.STR -> InferredTypes.knownFor(DataType.STR)
            fromDt istype DataType.STR_S && toDt istype DataType.STR_S -> InferredTypes.knownFor(DataType.STR_S)
            fromDt istype DataType.WORD || toDt istype DataType.WORD -> InferredTypes.knownFor(DataType.ARRAY_W)
            fromDt istype DataType.BYTE || toDt istype DataType.BYTE -> InferredTypes.knownFor(DataType.ARRAY_B)
            else -> InferredTypes.knownFor(DataType.ARRAY_UB)
        }
    }
    override fun toString(): String {
        return "RangeExpr(from $from, to $to, step $step, pos=$position)"
    }

    fun size(): Int? {
        val fromLv = (from as? NumericLiteralValue)
        val toLv = (to as? NumericLiteralValue)
        if(fromLv==null || toLv==null)
            return null
        return toConstantIntegerRange()?.count()
    }

    fun toConstantIntegerRange(): IntProgression? {
        val fromVal: Int
        val toVal: Int
        val fromString = from as? StringLiteralValue
        val toString = to as? StringLiteralValue
        if(fromString!=null && toString!=null ) {
            // string range -> int range over petscii values
            fromVal = CompilationTarget.encodeString(fromString.value)[0].toInt()
            toVal = CompilationTarget.encodeString(toString.value)[0].toInt()
        } else {
            val fromLv = from as? NumericLiteralValue
            val toLv = to as? NumericLiteralValue
            if(fromLv==null || toLv==null)
                return null         // non-constant range
            // integer range
            fromVal = fromLv.number.toInt()
            toVal = toLv.number.toInt()
        }
        val stepVal = (step as? NumericLiteralValue)?.number?.toInt() ?: 1
        return makeRange(fromVal, toVal, stepVal)
    }
}

internal fun makeRange(fromVal: Int, toVal: Int, stepVal: Int): IntProgression {
    return when {
        fromVal <= toVal -> when {
            stepVal <= 0 -> IntRange.EMPTY
            stepVal == 1 -> fromVal..toVal
            else -> fromVal..toVal step stepVal
        }
        else -> when {
            stepVal >= 0 -> IntRange.EMPTY
            stepVal == -1 -> fromVal downTo toVal
            else -> fromVal downTo toVal step abs(stepVal)
        }
    }
}

class RegisterExpr(val register: Register, override val position: Position) : Expression(), IAssignable {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
    }

    override fun constValue(program: Program): NumericLiteralValue? = null
    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String): Boolean = register.name in name
    override fun toString(): String {
        return "RegisterExpr(register=$register, pos=$position)"
    }

    override fun inferType(program: Program): InferredTypes.InferredType = InferredTypes.knownFor(DataType.UBYTE)
}

data class IdentifierReference(val nameInSource: List<String>, override val position: Position) : Expression(), IAssignable {
    override lateinit var parent: Node

    fun targetStatement(namespace: INameScope) =
        if(nameInSource.size==1 && nameInSource[0] in BuiltinFunctions)
            BuiltinFunctionStatementPlaceholder(nameInSource[0], position)
        else
            namespace.lookup(nameInSource, this)

    fun targetVarDecl(namespace: INameScope): VarDecl? = targetStatement(namespace) as? VarDecl
    fun targetSubroutine(namespace: INameScope): Subroutine? = targetStatement(namespace) as? Subroutine

    override fun linkParents(parent: Node) {
        this.parent = parent
    }

    override fun constValue(program: Program): NumericLiteralValue? {
        val node = program.namespace.lookup(nameInSource, this)
                ?: throw UndefinedSymbolError(this)
        val vardecl = node as? VarDecl
        if(vardecl==null) {
            return null
        } else if(vardecl.type!= VarDeclType.CONST) {
            return null
        }
        return vardecl.value?.constValue(program)
    }

    override fun toString(): String {
        return "IdentifierRef($nameInSource)"
    }

    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String): Boolean = nameInSource.last() in name

    override fun inferType(program: Program): InferredTypes.InferredType {
        val targetStmt = targetStatement(program.namespace)
        if(targetStmt is VarDecl) {
            return InferredTypes.knownFor(targetStmt.datatype)
        } else {
            throw FatalAstException("cannot get datatype from identifier reference ${this}, pos=$position")
        }
    }

    fun memberOfStruct(namespace: INameScope) = this.targetVarDecl(namespace)?.struct

    fun heapId(namespace: INameScope): Int {
        val node = namespace.lookup(nameInSource, this) ?: throw UndefinedSymbolError(this)
        val value = (node as? VarDecl)?.value ?: throw FatalAstException("requires a reference value")
        return when (value) {
            is IdentifierReference -> value.heapId(namespace)
            is StringLiteralValue -> value.heapId ?: throw FatalAstException("string is not on the heap: $value")
            is ArrayLiteralValue -> value.heapId ?: throw FatalAstException("array is not on the heap: $value")
            else -> throw FatalAstException("requires a reference value")
        }
    }
}

class FunctionCall(override var target: IdentifierReference,
                   override var arglist: MutableList<Expression>,
                   override val position: Position) : Expression(), IFunctionCall {
    override lateinit var parent: Node

    override fun linkParents(parent: Node) {
        this.parent = parent
        target.linkParents(this)
        arglist.forEach { it.linkParents(this) }
    }

    override fun constValue(program: Program) = constValue(program, true)

    private fun constValue(program: Program, withDatatypeCheck: Boolean): NumericLiteralValue? {
        // if the function is a built-in function and the args are consts, should try to const-evaluate!
        // lenghts of arrays and strings are constants that are determined at compile time!
        if(target.nameInSource.size>1) return null
        try {
            var resultValue: NumericLiteralValue? = null
            val func = BuiltinFunctions[target.nameInSource[0]]
            if(func!=null) {
                val exprfunc = func.constExpressionFunc
                if(exprfunc!=null)
                    resultValue = exprfunc(arglist, position, program)
                else if(func.returntype==null)
                    throw ExpressionError("builtin function ${target.nameInSource[0]} can't be used here because it doesn't return a value", position)
            }

            if(withDatatypeCheck) {
                val resultDt = this.inferType(program)
                if(resultValue==null || resultDt istype resultValue.type)
                    return resultValue
                throw FatalAstException("evaluated const expression result value doesn't match expected datatype $resultDt, pos=$position")
            } else {
                return resultValue
            }
        }
        catch(x: NotConstArgumentException) {
            // const-evaluating the builtin function call failed.
            return null
        }
    }

    override fun toString(): String {
        return "FunctionCall(target=$target, pos=$position)"
    }

    override fun accept(visitor: IAstModifyingVisitor) = visitor.visit(this)
    override fun accept(visitor: IAstVisitor) = visitor.visit(this)
    override fun referencesIdentifiers(vararg name: String): Boolean = target.referencesIdentifiers(*name) || arglist.any{it.referencesIdentifiers(*name)}

    override fun inferType(program: Program): InferredTypes.InferredType {
        val constVal = constValue(program ,false)
        if(constVal!=null)
            return InferredTypes.knownFor(constVal.type)
        val stmt = target.targetStatement(program.namespace) ?: return InferredTypes.unknown()
        when (stmt) {
            is BuiltinFunctionStatementPlaceholder -> {
                if(target.nameInSource[0] == "set_carry" || target.nameInSource[0]=="set_irqd" ||
                        target.nameInSource[0] == "clear_carry" || target.nameInSource[0]=="clear_irqd") {
                    return InferredTypes.void() // these have no return value
                }
                return builtinFunctionReturnType(target.nameInSource[0], this.arglist, program)
            }
            is Subroutine -> {
                if(stmt.returntypes.isEmpty())
                    return InferredTypes.void()     // no return value
                if(stmt.returntypes.size==1)
                    return InferredTypes.knownFor(stmt.returntypes[0])
                return InferredTypes.unknown()     // has multiple return types... so not a single resulting datatype possible
            }
            else -> return InferredTypes.unknown()
        }
    }
}
