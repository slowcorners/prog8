package prog8.astvm

import prog8.ast.*
import java.awt.EventQueue


class VmExecutionException(msg: String?) : Exception(msg)

class VmTerminationException(msg: String?) : Exception(msg)

class VmBreakpointException : Exception("breakpoint")


class RuntimeVariables {
    fun define(scope: INameScope, name: String, initialValue: RuntimeValue) {
        val where = vars.getValue(scope)
        where[name] = initialValue
        vars[scope] = where
        println("DEFINE RUNTIMEVAR:  ${scope.name}.$name = $initialValue")    // TODO
    }

    fun set(scope: INameScope, name: String, value: RuntimeValue) {
        val where = vars.getValue(scope)
        val existing = where[name] ?: throw NoSuchElementException("no such runtime variable: ${scope.name}.$name")
        if(existing.type!=value.type)
            throw VmExecutionException("new value is of different datatype ${value.type} expected ${existing.type} for $name")
        where[name] = value
        vars[scope] = where
        println("SET RUNTIMEVAR:  ${scope.name}.$name = $value")    // TODO
    }

    fun get(scope: INameScope, name: String): RuntimeValue {
        val where = vars.getValue(scope)
        val value = where[name]
        if(value!=null)
            return value
        throw NoSuchElementException("no such runtime variable: ${scope.name}.$name")
    }

    private val vars = mutableMapOf<INameScope, MutableMap<String, RuntimeValue>>().withDefault { mutableMapOf() }
}


class AstVm(val program: Program) {
    val mem = Memory()
    var P_carry: Boolean = false
        private set
    var P_zero: Boolean = true
        private set
    var P_negative: Boolean = false
        private set
    var P_irqd: Boolean = false
        private set
    private var dialog = ScreenDialog()

    init {
        dialog.requestFocusInWindow()

        EventQueue.invokeLater {
            dialog.pack()
            dialog.isVisible = true
            dialog.start()
        }
    }

    fun run() {
        try {
            val init = VariablesInitializer(runtimeVariables, program.heap)
            init.process(program)
            val entrypoint = program.entrypoint() ?: throw VmTerminationException("no valid entrypoint found")
            executeSubroutine(entrypoint, emptyList())
            println("PROGRAM EXITED!")
            dialog.title = "PROGRAM EXITED"
        } catch(bp: VmBreakpointException) {
            println("Breakpoint: execution halted. Press enter to resume.")
            readLine()
        } catch (tx: VmTerminationException) {
            println("Execution halted: ${tx.message}")
        } catch (xx: VmExecutionException) {
            println("Execution error: ${xx.message}")
            throw xx
        }
    }

    private val runtimeVariables = RuntimeVariables()

    internal fun executeSubroutine(sub: INameScope, arguments: List<RuntimeValue>): List<RuntimeValue> {
        if (sub.statements.isEmpty())
            throw VmTerminationException("scope contains no statements: $sub")
        if(sub is Subroutine) {
            assert(!sub.isAsmSubroutine)
            // TODO process arguments if it's a subroutine
        }
        for (s in sub.statements) {
            if(s is Return) {
                return s.values.map { evaluate(it, program, runtimeVariables, ::executeSubroutine) }
            }
            executeStatement(sub, s)
        }
        if(sub !is AnonymousScope)
            throw VmTerminationException("instruction pointer overflow, is a return missing? $sub")
        return emptyList()
    }

    private fun executeStatement(sub: INameScope, stmt: IStatement) {
        when (stmt) {
            is NopStatement, is Label, is Subroutine -> {
                // do nothing, skip this instruction
            }
            is Directive -> {
                if(stmt.directive=="%breakpoint")
                    throw VmBreakpointException()
                else if(stmt.directive=="%asm")
                    throw VmExecutionException("can't execute assembly code")
            }
            is VarDecl -> {
                // should have been defined already when the program started
            }
            is FunctionCallStatement -> {
                val target = stmt.target.targetStatement(program.namespace)
                when(target) {
                    is Subroutine -> {
                        val args = evaluate(stmt.arglist)
                        if(target.isAsmSubroutine) {
                            performSyscall(target, args)
                        } else {
                            val results = executeSubroutine(target, args)
                            // TODO process result values
                        }
                    }
                    is BuiltinFunctionStatementPlaceholder -> {
                        val args = evaluate(stmt.arglist)
                        performBuiltinFunction(target.name, args)
                    }
                    else -> {
                        TODO("CALL $target")
                    }
                }
            }
            is BuiltinFunctionStatementPlaceholder -> {
                TODO("$stmt")
            }
            is Return -> {
                throw VmExecutionException("return statement should have been handled by the subroutine loop")
            }
            is Continue -> {
                TODO("$stmt")
            }
            is Break -> {
                TODO("$stmt")
            }
            is Assignment -> {
                if(stmt.aug_op==null) {
                    val target = stmt.singleTarget
                    if(target!=null) {
                        when {
                            target.identifier!=null -> {
                                val ident = stmt.definingScope().lookup(target.identifier.nameInSource, stmt) as VarDecl
                                val value = evaluate(stmt.value, program, runtimeVariables, ::executeSubroutine)
                                val identScope = ident.definingScope()
                                runtimeVariables.set(identScope, ident.name, value)
                            }
                            target.memoryAddress!=null -> {
                                TODO("$stmt")
                            }
                            target.arrayindexed!=null -> {
                                val array = evaluate(target.arrayindexed.identifier, program, runtimeVariables, ::executeSubroutine)
                                val index = evaluate(target.arrayindexed.arrayspec.index, program, runtimeVariables, ::executeSubroutine)
                                val value = evaluate(stmt.value, program, runtimeVariables, ::executeSubroutine)
                                when(array.type) {
                                    DataType.ARRAY_UB -> {
                                        if(value.type!=DataType.UBYTE)
                                            throw VmExecutionException("new value is of different datatype ${value.type} for $array")
                                    }
                                    DataType.ARRAY_B -> {
                                        if(value.type!=DataType.BYTE)
                                            throw VmExecutionException("new value is of different datatype ${value.type} for $array")
                                    }
                                    DataType.ARRAY_UW -> {
                                        if(value.type!=DataType.UWORD)
                                            throw VmExecutionException("new value is of different datatype ${value.type} for $array")
                                    }
                                    DataType.ARRAY_W -> {
                                        if(value.type!=DataType.WORD)
                                            throw VmExecutionException("new value is of different datatype ${value.type} for $array")
                                    }
                                    DataType.ARRAY_F -> {
                                        if(value.type!=DataType.FLOAT)
                                            throw VmExecutionException("new value is of different datatype ${value.type} for $array")
                                    }
                                    else -> throw VmExecutionException("strange array type ${array.type}")
                                }
                                array.array!![index.integerValue()] = value.numericValue()
                            }
                        }
                    }
                    else TODO("$stmt")
                } else TODO("$stmt")
            }
            is PostIncrDecr -> {
                TODO("$stmt")
            }
            is Jump -> {
                TODO("$stmt")
            }
            is InlineAssembly -> {
                throw VmExecutionException("can't execute inline assembly in $sub")
            }
            is AnonymousScope -> {
                throw VmExecutionException("anonymous scopes should have been flattened")
            }
            is IfStatement -> {
                TODO("$stmt")
            }
            is BranchStatement -> {
                TODO("$stmt")
            }
            is ForLoop -> {
                TODO("$stmt")
            }
            is WhileLoop -> {
                var condition = evaluate(stmt.condition, program, runtimeVariables, ::executeSubroutine)
                while(condition.asBooleanRuntimeValue) {
                    println("STILL IN WHILE LOOP ${stmt.position}")
                    executeSubroutine(stmt.body, emptyList())
                    condition = evaluate(stmt.condition, program, runtimeVariables, ::executeSubroutine)
                }
                println(">>>>WHILE LOOP EXITED")
            }
            is RepeatLoop -> {
                do {
                    val condition = evaluate(stmt.untilCondition, program, runtimeVariables, ::executeSubroutine)
                    executeSubroutine(stmt.body, emptyList())
                } while(!condition.asBooleanRuntimeValue)
            }
            else -> {
                TODO("implement $stmt")
            }
        }
    }


    private fun evaluate(args: List<IExpression>): List<RuntimeValue>  = args.map { evaluate(it, program, runtimeVariables, ::executeSubroutine) }

    private fun performBuiltinFunction(name: String, args: List<RuntimeValue>) {
        when(name) {
            "memset" -> {
                val target = args[0].array!!
                val amount = args[1].integerValue()
                val value = args[2].integerValue()
                for(i in 0 until amount) {
                    target[i] = value
                }
            }
            else -> TODO("builtin function $name")
        }
    }

    private fun performSyscall(sub: Subroutine, args: List<RuntimeValue>) {
        assert(sub.isAsmSubroutine)
        when(sub.scopedname) {
            "c64scr.print" -> {
                // if the argument is an UWORD, consider it to be the "address" of the string (=heapId)
                if(args[0].wordval!=null) {
                    val str = program.heap.get(args[0].wordval!!).str!!
                    dialog.canvas.printText(str, 1, true)
                }
                else
                    dialog.canvas.printText(args[0].str!!, 1, true)
            }
            "c64scr.print_ub" -> {
                dialog.canvas.printText(args[0].byteval!!.toString(), 1, true)
            }
            "c64scr.print_uw" -> {
                dialog.canvas.printText(args[0].wordval!!.toString(), 1, true)
            }
            "c64.CHROUT" -> {
                dialog.canvas.printChar(args[0].byteval!!)
            }
            else -> TODO("syscall $sub")
        }
    }


    private fun setFlags(value: LiteralValue?) {
        if(value!=null) {
            when(value.type) {
                DataType.UBYTE -> {
                    val v = value.bytevalue!!.toInt()
                    P_negative = v>127
                    P_zero = v==0
                }
                DataType.BYTE -> {
                    val v = value.bytevalue!!.toInt()
                    P_negative = v<0
                    P_zero = v==0
                }
                DataType.UWORD -> {
                    val v = value.wordvalue!!
                    P_negative = v>32767
                    P_zero = v==0
                }
                DataType.WORD -> {
                    val v = value.wordvalue!!
                    P_negative = v<0
                    P_zero = v==0
                }
                DataType.FLOAT -> {
                    val flt = value.floatvalue!!
                    P_negative = flt < 0.0
                    P_zero = flt==0.0
                }
                else -> {
                    // no flags for non-numeric type
                }
            }
        }
    }
}
