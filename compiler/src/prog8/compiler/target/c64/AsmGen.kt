package prog8.compiler.target.c64

// note: to put stuff on the stack, we use Absolute,X  addressing mode which is 3 bytes / 4 cycles
// possible space optimization is to use zeropage (indirect),Y  which is 2 bytes, but 5 cycles


import prog8.ast.DataType
import prog8.ast.Register
import prog8.compiler.*
import prog8.compiler.intermediate.*
import prog8.stackvm.Syscall
import prog8.stackvm.syscallsForStackVm
import java.io.File
import java.io.PrintWriter
import java.util.*
import kotlin.math.abs


private val registerStrings = Register.values().map{it.toString()}.toSet()

class AssemblyError(msg: String) : RuntimeException(msg)


class AsmGen(val options: CompilationOptions, val program: IntermediateProgram, val heap: HeapValues) {
    private val globalFloatConsts = mutableMapOf<Double, String>()
    private val assemblyLines = mutableListOf<String>()
    private lateinit var block: IntermediateProgram.ProgramBlock
    private var breakpointCounter = 0

    init {
        // Because 64tass understands scoped names via .proc / .block,
        // we'll strip the block prefix from all scoped names in the program.
        // Also, convert invalid label names (such as "<<<anonymous-1>>>") to something that's allowed.
        val newblocks = mutableListOf<IntermediateProgram.ProgramBlock>()
        for(block in program.blocks) {
            val newvars = block.variables.map { symname(it.key, block) to it.value }.toMap().toMutableMap()
            val newlabels = block.labels.map { symname(it.key, block) to it.value}.toMap().toMutableMap()
            val newinstructions = block.instructions.asSequence().map {
                when {
                    it is LabelInstr -> LabelInstr(symname(it.name, block))
                    it.opcode == Opcode.INLINE_ASSEMBLY -> it
                    else ->
                        Instruction(it.opcode, it.arg, it.arg2,
                            callLabel = if (it.callLabel != null) symname(it.callLabel, block) else null,
                            callLabel2 = if (it.callLabel2 != null) symname(it.callLabel2, block) else null)
                }
            }.toMutableList()
            val newConstants = block.integerConstants.map { symname(it.key, block) to it.value }.toMap().toMutableMap()
            newblocks.add(IntermediateProgram.ProgramBlock(
                    block.scopedname,
                    block.shortname,
                    block.address,
                    newinstructions,
                    newvars,
                    newConstants,
                    newlabels))
        }
        program.blocks.clear()
        program.blocks.addAll(newblocks)

        // make a list of all const floats that are used
        for(block in program.blocks) {
            for(ins in block.instructions.filter{it.arg?.type==DataType.FLOAT}) {
                val float = ins.arg!!.numericValue().toDouble()
                if(float !in globalFloatConsts)
                    globalFloatConsts[float] = "prog8_const_float_${globalFloatConsts.size}"
            }
        }
    }

    fun compileToAssembly(): AssemblyProgram {
        println("\nGenerating assembly code from intermediate code... ")

        header()
        for(b in program.blocks)
            block2asm(b)

        optimizeAssembly(assemblyLines)

        File("${program.name}.asm").printWriter().use {
            for (line in assemblyLines) { it.println(line) }
        }

        return AssemblyProgram(program.name)
    }

    private fun optimizeAssembly(lines: MutableList<String>) {
        // sometimes, iny+dey / inx+dex / dey+iny / dex+inx sequences are generated, these can be eliminated.
        val removeLines = mutableListOf<Int>()
        for(pair in lines.withIndex().windowed(2)) {
            val first = pair[0].value
            val second = pair[1].value
            if(first.trimStart().startsWith(';') || second.trimStart().startsWith(';'))
                continue        // skip over asm comments

            if((" iny" in first || "\tiny" in first) && (" dey" in second || "\tdey" in second)
                    || (" inx" in first || "\tinx" in first) && (" dex" in second || "\tdex" in second)
                    || (" dey" in first || "\tdey" in first) && (" iny" in second || "\tiny" in second)
                    || (" dex" in first || "\tdex" in first) && (" inx" in second || "\tinx" in second))
            {
                removeLines.add(pair[0].index)
                removeLines.add(pair[1].index)
            }
        }

        for(i in removeLines.reversed())
            lines.removeAt(i)
    }

    private fun out(str: String) = assemblyLines.add(str)

    private fun symname(scoped: String, block: IntermediateProgram.ProgramBlock): String {
        if(' ' in scoped)
            return scoped

        val blockLocal: Boolean
        var name = if (scoped.startsWith("${block.shortname}.")) {
            blockLocal = true
            scoped.substring(block.shortname.length+1)
        } else {
            blockLocal = false
            scoped
        }
        name = name.replace("<<<", "prog8_").replace(">>>", "")
        if(name=="-")
            return "-"
        if(blockLocal)
            name = name.replace(".", "_")
        else {
            val parts = name.split(".", limit=2)
            if(parts.size>1)
                name = "${parts[0]}.${parts[1].replace(".", "_")}"
        }
        return name.replace("-", "")
    }

    private fun makeFloatFill(flt: Mflpt5): String {
        val b0 = "$"+flt.b0.toString(16).padStart(2, '0')
        val b1 = "$"+flt.b1.toString(16).padStart(2, '0')
        val b2 = "$"+flt.b2.toString(16).padStart(2, '0')
        val b3 = "$"+flt.b3.toString(16).padStart(2, '0')
        val b4 = "$"+flt.b4.toString(16).padStart(2, '0')
        return "$b0, $b1, $b2, $b3, $b4"
    }

    private fun header() {
        val ourName = this.javaClass.name
        out("; 6502 assembly code for '${program.name}'")
        out("; generated by $ourName on ${Date()}")
        out("; assembler syntax is for the 64tasm cross-assembler")
        out("; output options: output=${options.output} launcher=${options.launcher} zp=${options.zeropage}")
        out("\n.cpu  '6502'\n.enc  'none'\n")

        if(program.loadAddress==0)   // fix load address
            program.loadAddress = if(options.launcher==LauncherType.BASIC) BASIC_LOAD_ADDRESS else RAW_LOAD_ADDRESS

        when {
            options.launcher == LauncherType.BASIC -> {
                if (program.loadAddress != 0x0801)
                    throw AssemblyError("BASIC output must have load address $0801")
                out("; ---- basic program with sys call ----")
                out("* = ${program.loadAddress.toHex()}")
                val year = Calendar.getInstance().get(Calendar.YEAR)
                out("\t.word  (+), $year")
                out("\t.null  $9e, format(' %d ', _prog8_entrypoint), $3a, $8f, ' prog8 by idj'")
                out("+\t.word  0")
                out("_prog8_entrypoint\t; assembly code starts here\n")
                out("\tjsr  c64utils.init_system")
            }
            options.output == OutputType.PRG -> {
                out("; ---- program without sys call ----")
                out("* = ${program.loadAddress.toHex()}\n")
                out("\tjsr  c64utils.init_system")
            }
            options.output == OutputType.RAW -> {
                out("; ---- raw assembler program ----")
                out("* = ${program.loadAddress.toHex()}\n")
            }
        }

        out("\tldx  #\$ff\t; init estack pointer")
        out("\tclc")
        out("\tjmp  main.start\t; jump to program entrypoint")
        out("")

        // the global list of all floating point constants for the whole program
        for(flt in globalFloatConsts) {
            val floatFill = makeFloatFill(Mflpt5.fromNumber(flt.key))
            out("${flt.value}\t.byte  $floatFill  ; float ${flt.key}")
        }
    }

    private fun block2asm(blk: IntermediateProgram.ProgramBlock) {
        block = blk
        out("\n; ---- block: '${block.shortname}' ----")
        if(block.address!=null) {
            out(".cerror * > ${block.address?.toHex()}, 'block address overlaps by ', *-${block.address?.toHex()},' bytes'")
            out("* = ${block.address?.toHex()}")
        }
        out("${block.shortname}\t.proc\n")
        out("\n; memdefs and kernel subroutines")
        memdefs2asm(block)
        out("\n; variables")
        vardecls2asm(block)
        out("")

        val instructionPatternWindowSize = 6
        var processed = 0
        for(ins in block.instructions.windowed(instructionPatternWindowSize, partialWindows = true)) {
            if(processed==0) {
                processed = instr2asm(ins)
                if(processed == 0)
                    throw CompilerException("no asm translation found for instruction pattern: $ins")
            }
            processed--
        }
        out("\n\t.pend\n")
    }

    private fun memdefs2asm(block: IntermediateProgram.ProgramBlock) {
        for(m in block.integerConstants) {
            out("\t${m.key} = ${m.value.toHex()}")
        }
    }

    private fun vardecls2asm(block: IntermediateProgram.ProgramBlock) {
        val sortedVars = block.variables.toList().sortedBy { it.second.type }
        for (v in sortedVars) {
            when (v.second.type) {
                DataType.UBYTE -> out("${v.first}\t.byte  0")
                DataType.BYTE -> out("${v.first}\t.char  0")
                DataType.UWORD -> out("${v.first}\t.word  0")
                DataType.WORD -> out("${v.first}\t.sint  0")
                DataType.FLOAT -> out("${v.first}\t.fill  5  ; float")
                DataType.STR,
                DataType.STR_P,
                DataType.STR_S,
                DataType.STR_PS -> {
                    val rawStr = heap.get(v.second.heapId).str!!
                    val bytes = encodeStr(rawStr, v.second.type).map { "$" + it.toString(16).padStart(2, '0') }
                    out("${v.first}\t; ${v.second.type} \"$rawStr\"")
                    for (chunk in bytes.chunked(16))
                        out("\t.byte  " + chunk.joinToString())
                }
                DataType.ARRAY_UB -> {
                    // unsigned integer byte arrayspec
                    val data = makeArrayFillDataUnsigned(v.second)
                    if (data.size <= 16)
                        out("${v.first}\t.byte  ${data.joinToString()}")
                    else {
                        out(v.first)
                        for (chunk in data.chunked(16))
                            out("\t.byte  " + chunk.joinToString())
                    }
                }
                DataType.ARRAY_B -> {
                    // signed integer byte arrayspec
                    val data = makeArrayFillDataSigned(v.second)
                    if (data.size <= 16)
                        out("${v.first}\t.char  ${data.joinToString()}")
                    else {
                        out(v.first)
                        for (chunk in data.chunked(16))
                            out("\t.char  " + chunk.joinToString())
                    }
                }
                DataType.ARRAY_UW -> {
                    // unsigned word arrayspec
                    val data = makeArrayFillDataUnsigned(v.second)
                    if (data.size <= 16)
                        out("${v.first}\t.word  ${data.joinToString()}")
                    else {
                        out(v.first)
                        for (chunk in data.chunked(16))
                            out("\t.word  " + chunk.joinToString())
                    }
                }
                DataType.ARRAY_W -> {
                    // signed word arrayspec
                    val data = makeArrayFillDataSigned(v.second)
                    if (data.size <= 16)
                        out("${v.first}\t.sint  ${data.joinToString()}")
                    else {
                        out(v.first)
                        for (chunk in data.chunked(16))
                            out("\t.sint  " + chunk.joinToString())
                    }
                }
                DataType.ARRAY_F -> {
                    // float arrayspec
                    val array = heap.get(v.second.heapId).doubleArray!!
                    val floatFills = array.map { makeFloatFill(Mflpt5.fromNumber(it)) }
                    out(v.first)
                    for(f in array.zip(floatFills))
                        out("\t.byte  ${f.second}  ; float ${f.first}")
                }
            }
        }
    }

    private fun encodeStr(str: String, dt: DataType): List<Short> {
        when(dt) {
            DataType.STR -> {
                val bytes = Petscii.encodePetscii(str, true)
                return bytes.plus(0)
            }
            DataType.STR_P -> {
                val result = listOf(str.length.toShort())
                val bytes = Petscii.encodePetscii(str, true)
                return result.plus(bytes)
            }
            DataType.STR_S -> {
                val bytes = Petscii.encodeScreencode(str, true)
                return bytes.plus(0)
            }
            DataType.STR_PS -> {
                val result = listOf(str.length.toShort())
                val bytes = Petscii.encodeScreencode(str, true)
                return result.plus(bytes)
            }
            else -> throw AssemblyError("invalid str type")
        }
    }

    private fun makeArrayFillDataUnsigned(value: Value): List<String> {
        val array = heap.get(value.heapId).array!!
        return if (value.type == DataType.ARRAY_UB || value.type == DataType.ARRAY_UW)
            array.map { "$"+it.toString(16).padStart(2, '0') }
        else
            throw AssemblyError("invalid arrayspec type")
    }

    private fun makeArrayFillDataSigned(value: Value): List<String> {
        val array = heap.get(value.heapId).array!!
        return if (value.type == DataType.ARRAY_B || value.type == DataType.ARRAY_W) {
            array.map {
                if(it>=0)
                    "$"+it.toString(16).padStart(2, '0')
                else
                    "-$"+abs(it).toString(16).padStart(2, '0')
            }
        }
        else throw AssemblyError("invalid arrayspec type")
    }

    private fun instr2asm(ins: List<Instruction>): Int {
        // find best patterns (matching the most of the lines, then with the smallest weight)
        val fragments = findPatterns(ins).sortedByDescending { it.segmentSize }
        if(fragments.isEmpty()) {
            // we didn't find any matching patterns (complex multi-instruction fragments), try simple ones
            val firstIns = ins[0]
            val singleAsm = simpleInstr2Asm(firstIns)
            if(singleAsm != null) {
                outputAsmFragment(singleAsm)
                return 1
            }
            return 0
        }
        val best = fragments[0]
        outputAsmFragment(best.asm)
        return best.segmentSize
    }

    private fun outputAsmFragment(singleAsm: String) {
        if (singleAsm.isNotEmpty()) {
            when {
                singleAsm.startsWith("@inline@") -> out(singleAsm.substring(8))
                '\n' in singleAsm -> for (line in singleAsm.split('\n')) {
                    if (line.isNotEmpty()) {
                        val trimmed = if (line.startsWith(' ')) "\t" + line.trim() else line.trim()
                        out(trimmed)
                    }
                }
                else -> for (line in singleAsm.split('|')) {
                    val trimmed = if (line.startsWith(' ')) "\t" + line.trim() else line.trim()
                    out(trimmed)
                }
            }
        }
    }

    private fun getFloatConst(value: Value): String =
            globalFloatConsts[value.numericValue().toDouble()]
                    ?: throw AssemblyError("should have a global float const for number $value")

    private fun simpleInstr2Asm(ins: Instruction): String? {
        // a label 'instruction' is simply translated into a asm label
        if(ins is LabelInstr) {
            if(ins.name==block.shortname)
                return ""
            return if(ins.name.startsWith("${block.shortname}."))
                ins.name.substring(block.shortname.length+1)
            else
                ins.name
        }

        // simple opcodes that are translated directly into one or a few asm instructions
        return when(ins.opcode) {
            Opcode.LINE -> " ;\tsrc line: ${ins.callLabel}"
            Opcode.NOP -> " nop"      // shouldn't be present anymore though
            Opcode.TERMINATE -> " brk"
            Opcode.SEC -> " sec"
            Opcode.CLC -> " clc"
            Opcode.SEI -> " sei"
            Opcode.CLI -> " cli"
            Opcode.JUMP -> " jmp  ${ins.callLabel}"
            Opcode.CALL -> " jsr  ${ins.callLabel}"
            Opcode.RETURN -> " rts"
            Opcode.B2UB -> ""   // is a no-op, just carry on with the byte as-is
            Opcode.UB2B -> ""   // is a no-op, just carry on with the byte as-is
            Opcode.RSAVE -> " php |  pha |  txa |  pha |  tya |  pha"
            Opcode.RRESTORE -> " pla |  tay |  pla |  tax |  pla |  plp"
            Opcode.DISCARD_BYTE -> " inx"
            Opcode.DISCARD_WORD -> " inx"
            Opcode.DISCARD_FLOAT -> " inx |  inx |  inx"
            Opcode.INLINE_ASSEMBLY ->  "@inline@" + (ins.callLabel ?: "")      // All of the inline assembly is stored in the calllabel property.
            Opcode.SYSCALL -> {
                if (ins.arg!!.numericValue() in syscallsForStackVm.map { it.callNr })
                    throw CompilerException("cannot translate vm syscalls to real assembly calls - use *real* subroutine calls instead. Syscall ${ins.arg.numericValue()}")
                val call = Syscall.values().find { it.callNr==ins.arg.numericValue() }
                " jsr  prog8_lib.${call.toString().toLowerCase()}"
            }
            Opcode.BREAKPOINT -> {
                breakpointCounter++
                "_prog8_breakpoint_$breakpointCounter\tnop"
            }

//            Opcode.PUSH_BYTE -> {
//                " lda  #${ins.arg!!.integerValue().toHex()} |  sta  ${ESTACK_LO.toHex()},x |  dex"
//            }
//            Opcode.PUSH_WORD -> {
//                val value = ins.arg!!.integerValue().toHex()
//                " lda  #<$value |  sta  ${ESTACK_LO.toHex()},x |  lda  #>$value |  sta  ${ESTACK_HI.toHex()},x |  dex"
//            }
//            Opcode.PUSH_FLOAT -> {
//                val floatConst = getFloatConst(ins.arg!!)
//                " lda  #<$floatConst |  ldy  #>$floatConst |  jsr  prog8_lib.push_float"
//            }
//            Opcode.PUSH_VAR_BYTE -> {
//                when(ins.callLabel) {
//                    "X" -> throw CompilerException("makes no sense to push X, it's used as a stack pointer itself")
//                    "A" -> " sta  ${ESTACK_LO.toHex()},x |  dex"
//                    "Y" -> " tya |  sta  ${ESTACK_LO.toHex()},x |  dex"
//                    else -> " lda  ${ins.callLabel} |  sta  ${ESTACK_LO.toHex()},x |  dex"
//                }
//            }
//            Opcode.PUSH_VAR_WORD -> {
//                when (ins.callLabel) {
//                    "AX" -> throw CompilerException("makes no sense to push X, it's used as a stack pointer itself")
//                    "XY" -> throw CompilerException("makes no sense to push X, it's used as a stack pointer itself")
//                    "AY" -> " sta  ${ESTACK_LO.toHex()},x |  pha |  tya |  sta  ${ESTACK_HI.toHex()},x |  pla |  dex"
//                    else -> " lda  ${ins.callLabel} |  ldy  ${ins.callLabel}+1 |  sta  ${ESTACK_LO.toHex()},x |  pha |  tya |  sta  ${ESTACK_HI.toHex()},x |  pla |  dex"
//                }
//            }
//            Opcode.PUSH_VAR_FLOAT -> " lda  #<${ins.callLabel} |  ldy  #>${ins.callLabel}|  jsr  prog8_lib.push_float"
//            Opcode.PUSH_MEM_B, Opcode.PUSH_MEM_UB -> {
//                """
//                lda  ${ins.arg!!.integerValue().toHex()}
//                sta  ${ESTACK_LO.toHex()},x
//                dex
//                """
//            }
//            Opcode.PUSH_MEM_W, Opcode.PUSH_MEM_UW -> {
//                """
//                lda  ${ins.arg!!.integerValue().toHex()}
//                sta  ${ESTACK_LO.toHex()},x
//                lda  ${(ins.arg.integerValue()+1).toHex()}
//                sta  ${ESTACK_HI.toHex()},x
//                dex
//                """
//            }

//            Opcode.READ_INDEXED_VAR_BYTE -> {           // @todo is this correct?
//                """
//                ldy  ${(ESTACK_LO+1).toHex()},x
//                lda  ${ins.callLabel},y
//                sta  ${(ESTACK_LO+1).toHex()},x
//                """
//            }

            Opcode.WRITE_INDEXED_VAR_BYTE -> {      // @todo is this correct?
                """
                inx
                ldy  ${ESTACK_LO.toHex()},x
                inx
                lda  ${ESTACK_LO.toHex()},x
                sta  ${ins.callLabel},y
                """
            }
            Opcode.POP_MEM_BYTE -> {
                """
                inx
                lda  ${ESTACK_LO.toHex()},x
                sta  ${ins.arg!!.integerValue().toHex()}
                """
            }
            Opcode.POP_MEM_WORD -> {
                """
                inx
                lda  ${ESTACK_LO.toHex()},x
                sta  ${ins.arg!!.integerValue().toHex()}
                lda  ${ESTACK_HI.toHex()},x
                sta  ${(ins.arg.integerValue()+1).toHex()}
                """
            }
            Opcode.POP_VAR_BYTE -> {
                when (ins.callLabel) {
                    "X" -> throw CompilerException("makes no sense to pop X, it's used as a stack pointer itself")
                    "A" -> " inx |  lda  ${ESTACK_LO.toHex()},x"
                    "Y" -> " inx |  ldy  ${ESTACK_LO.toHex()},x"
                    else -> " inx |  lda  ${ESTACK_LO.toHex()},x |  sta  ${ins.callLabel}"
                }
            }
            Opcode.POP_VAR_WORD -> {
                when (ins.callLabel) {
                    "AX" -> throw CompilerException("makes no sense to pop X, it's used as a stack pointer itself")
                    "XY" -> throw CompilerException("makes no sense to pop X, it's used as a stack pointer itself")
                    "AY" -> " inx |  lda  ${ESTACK_LO.toHex()},x |  ldy  ${ESTACK_HI.toHex()},x"
                    else -> " inx |  lda  ${ESTACK_LO.toHex()},x |  ldy  ${ESTACK_HI.toHex()},x |  sta  ${ins.callLabel} |  sty  ${ins.callLabel}+1"
                }
            }
            Opcode.POP_VAR_FLOAT -> {
                " lda  #<${ins.callLabel} |  ldy  #>${ins.callLabel} |  jsr  prog8_lib.pop_var_float"
            }

            Opcode.INC_VAR_UB, Opcode.INC_VAR_B -> {
                when (ins.callLabel) {
                    "A" -> " clc |  adc  #1"
                    "X" -> " inx"
                    "Y" -> " iny"
                    else -> " inc  ${ins.callLabel}"
                }
            }
            Opcode.INC_VAR_UW -> {
                when (ins.callLabel) {
                    "AX" -> " clc |  adc  #1 |  bne  + |  inx |+"
                    "AY" -> " clc |  adc  #1 |  bne  + |  iny |+"
                    "XY" -> " inx |  bne  + |  iny  |+"
                    else -> " inc  ${ins.callLabel} |  bne  + |  inc  ${ins.callLabel}+1 |+"
                }
            }
            Opcode.INC_VAR_F -> {
                """
                lda  #<${ins.callLabel}
                ldy  #>${ins.callLabel}
                jsr  prog8_lib.inc_var_f
                """
            }
            Opcode.DEC_VAR_UB, Opcode.DEC_VAR_B -> {
                when (ins.callLabel) {
                    "A" -> " sec |  sbc  #1"
                    "X" -> " dex"
                    "Y" -> " dey"
                    else -> " dec  ${ins.callLabel}"
                }
            }
            Opcode.DEC_VAR_UW -> {
                when (ins.callLabel) {
                    "AX" -> " cmp  #0 |  bne  + |  dex |+ |  sec |  sbc  #1"
                    "AY" -> " cmp  #0 |  bne  + |  dey |+ |  sec |  sbc  #1"
                    "XY" -> " txa |  bne + |  dey |+ | dex"
                    else -> " lda  ${ins.callLabel} |  bne  + |  dec  ${ins.callLabel}+1 |+ |  dec  ${ins.callLabel}"
                }
            }
            Opcode.DEC_VAR_F -> {
                """
                lda  #<${ins.callLabel}
                ldy  #>${ins.callLabel}
                jsr  prog8_lib.dec_var_f
                """
            }
            Opcode.NEG_B -> {
                """
                lda  ${(ESTACK_LO+1).toHex()},x
                eor  #255
                sec
                adc  #0
                sta  ${(ESTACK_LO+1).toHex()},x
                """
            }
            Opcode.NEG_F -> " jsr  prog8_lib.neg_f"
            Opcode.INV_BYTE -> {
                """
                lda  ${(ESTACK_LO + 1).toHex()},x
                eor  #255
                sta  ${(ESTACK_LO + 1).toHex()},x
                """
            }
            Opcode.INV_WORD -> {
                """
                lda  ${(ESTACK_LO + 1).toHex()},x
                eor  #255
                sta  ${(ESTACK_LO+1).toHex()},x
                lda  ${(ESTACK_HI + 1).toHex()},x
                eor  #255
                sta  ${(ESTACK_HI+1).toHex()},x
                """
            }
            Opcode.NOT_BYTE -> {
                """
                lda  ${(ESTACK_LO+1).toHex()},x
                beq  +
                lda  #0
                beq ++
+               lda  #1
+               sta  ${(ESTACK_LO+1).toHex()},x
                """
            }
            Opcode.NOT_WORD -> {
                """
                lda  ${(ESTACK_LO + 1).toHex()},x
                ora  ${(ESTACK_HI + 1).toHex()},x
                beq  +
                lda  #0
                beq  ++
+               lda  #1
+               sta  ${(ESTACK_LO + 1).toHex()},x |  sta  ${(ESTACK_HI + 1).toHex()},x
                """
            }

            Opcode.BCS -> " bcs  ${ins.callLabel}"
            Opcode.BCC -> " bcc  ${ins.callLabel}"
            Opcode.BNEG -> " bmi  ${ins.callLabel}"
            Opcode.BPOS -> " bpl  ${ins.callLabel}"
            Opcode.BVC -> " bvc  ${ins.callLabel}"
            Opcode.BVS -> " bvs  ${ins.callLabel}"
            Opcode.BZ -> " beq  ${ins.callLabel}"
            Opcode.BNZ -> " bne  ${ins.callLabel}"
            Opcode.UB2FLOAT -> " jsr  prog8_lib.ub2float"
            Opcode.B2FLOAT -> " jsr  prog8_lib.b2float"
            Opcode.UW2FLOAT -> " jsr  prog8_lib.uw2float"
            Opcode.W2FLOAT -> " jsr  prog8_lib.w2float"

            Opcode.DIV_UB -> "  jsr  prog8_lib.div_ub"
            Opcode.DIV_B -> "  jsr  prog8_lib.div_b"
            Opcode.DIV_F -> "  jsr  prog8_lib.div_f"
            Opcode.DIV_W -> "  jsr  prog8_lib.div_w"
            Opcode.DIV_UW -> "  jsr  prog8_lib.div_uw"
            Opcode.ADD_UB, Opcode.ADD_B -> {
                """
                lda  ${(ESTACK_LO + 2).toHex()},x
                clc
                adc  ${(ESTACK_LO + 1).toHex()},x
                inx
                sta  ${(ESTACK_LO + 1).toHex()},x
                """
            }
            Opcode.SUB_UB, Opcode.SUB_B -> {
                """
                lda  ${(ESTACK_LO + 2).toHex()},x
                sec
                sbc  ${(ESTACK_LO + 1).toHex()},x
                inx
                sta  ${(ESTACK_LO + 1).toHex()},x
                """
            }
            Opcode.ADD_F -> "  jsr  prog8_lib.add_f"
            Opcode.ADD_W -> "  jsr  prog8_lib.add_w"    // todo or inline?
            Opcode.ADD_UW -> "  jsr  prog8_lib.add_uw"  // todo or inline?
            Opcode.SUB_F -> "  jsr  prog8_lib.sub_f"
            Opcode.SUB_W -> "  jsr  prog8_lib.sub_w"    // todo or inline?
            Opcode.SUB_UW -> "  jsr  prog8_lib.sub_uw"    // todo or inline?
            Opcode.MUL_F -> "  jsr  prog8_lib.mul_f"
            Opcode.MUL_B -> "  jsr  prog8_lib.mul_b"
            Opcode.MUL_UB -> "  jsr  prog8_lib.mul_ub"
            Opcode.MUL_W -> "  jsr  prog8_lib.mul_w"
            Opcode.MUL_UW -> "  jsr  prog8_lib.mul_uw"
            Opcode.LESS_UB -> "  jsr  prog8_lib.less_ub"
            Opcode.LESS_B -> "  jsr  prog8_lib.less_b"
            Opcode.LESS_UW -> "  jsr  prog8_lib.less_uw"
            Opcode.LESS_W -> "  jsr  prog8_lib.less_w"
            Opcode.LESS_F -> "  jsr  prog8_lib.less_f"

            Opcode.AND_BYTE -> {
                """
                lda  ${(ESTACK_LO + 2).toHex()},x
                and  ${(ESTACK_LO + 1).toHex()},x
                inx
                sta  ${(ESTACK_LO + 1).toHex()},x
                """
            }
            Opcode.OR_BYTE -> {
                """
                lda  ${(ESTACK_LO + 2).toHex()},x
                ora  ${(ESTACK_LO + 1).toHex()},x
                inx
                sta  ${(ESTACK_LO + 1).toHex()},x
                """
            }

            else -> null
        }
    }

    private fun findPatterns(segment: List<Instruction>): List<AsmFragment> {
        val opcodes = segment.map { it.opcode }
        val result = mutableListOf<AsmFragment>()

        // check for operations that modify a single value, by putting it on the stack (and popping it afterwards)
        if((opcodes[0]==Opcode.PUSH_VAR_BYTE && opcodes[2]==Opcode.POP_VAR_BYTE) ||
                (opcodes[0]==Opcode.PUSH_VAR_WORD && opcodes[2]==Opcode.POP_VAR_WORD) ||
                (opcodes[0]==Opcode.PUSH_VAR_FLOAT && opcodes[2]==Opcode.POP_VAR_FLOAT)) {
            if (segment[0].callLabel == segment[2].callLabel) {
                val fragment = sameVarOperation(segment[0].callLabel!!, segment[1])
                if (fragment != null) {
                    fragment.segmentSize = 3
                    result.add(fragment)
                }
            }
        }
        else if((opcodes[0]==Opcode.PUSH_MEM_UB && opcodes[2]==Opcode.POP_MEM_BYTE) ||
                (opcodes[0]==Opcode.PUSH_MEM_B && opcodes[2]==Opcode.POP_MEM_BYTE) ||
                (opcodes[0]==Opcode.PUSH_MEM_UW && opcodes[2]==Opcode.POP_MEM_WORD) ||
                (opcodes[0]==Opcode.PUSH_MEM_W && opcodes[2]==Opcode.POP_MEM_WORD) ||
                (opcodes[0]==Opcode.PUSH_MEM_FLOAT && opcodes[2]==Opcode.POP_MEM_FLOAT)) {
            if(segment[0].arg==segment[2].arg) {
                val fragment = sameMemOperation(segment[0].arg!!.integerValue(), segment[1])
                if(fragment!=null) {
                    fragment.segmentSize = 3
                    result.add(fragment)
                }
            }
        }
        else if((opcodes[0]==Opcode.PUSH_BYTE && opcodes[1]==Opcode.READ_INDEXED_VAR_BYTE &&
                        opcodes[3]==Opcode.PUSH_BYTE && opcodes[4]==Opcode.WRITE_INDEXED_VAR_BYTE) ||
                (opcodes[0]==Opcode.PUSH_BYTE && opcodes[1]==Opcode.READ_INDEXED_VAR_WORD &&
                        opcodes[3]==Opcode.PUSH_BYTE && opcodes[4]==Opcode.WRITE_INDEXED_VAR_WORD)) {
            if(segment[0].arg==segment[3].arg && segment[1].callLabel==segment[4].callLabel) {
                val fragment = sameConstantIndexedVarOperation(segment[1].callLabel!!, segment[0].arg!!.integerValue(), segment[2])
                if(fragment!=null){
                    fragment.segmentSize = 5
                    result.add(fragment)
                }
            }
        }
        else if((opcodes[0]==Opcode.PUSH_VAR_BYTE && opcodes[1]==Opcode.READ_INDEXED_VAR_BYTE &&
                        opcodes[3]==Opcode.PUSH_VAR_BYTE && opcodes[4]==Opcode.WRITE_INDEXED_VAR_BYTE) ||
                (opcodes[0]==Opcode.PUSH_VAR_BYTE && opcodes[1]==Opcode.READ_INDEXED_VAR_WORD &&
                        opcodes[3]==Opcode.PUSH_VAR_BYTE && opcodes[4]==Opcode.WRITE_INDEXED_VAR_WORD)) {
            if(segment[0].callLabel==segment[3].callLabel && segment[1].callLabel==segment[4].callLabel) {
                val fragment = sameIndexedVarOperation(segment[1].callLabel!!, segment[0].callLabel!!, segment[2])
                if(fragment!=null){
                    fragment.segmentSize = 5
                    result.add(fragment)
                }
            }
        }

        for(pattern in patterns.filter { it.sequence.size <= segment.size || (it.altSequence != null && it.altSequence.size <= segment.size)}) {
            val opcodesList = opcodes.subList(0, pattern.sequence.size)
            if(pattern.sequence == opcodesList) {
                val asm = pattern.asm(segment)
                if(asm!=null)
                    result.add(AsmFragment(asm, pattern.sequence.size))
            } else if(pattern.altSequence == opcodesList) {
                val asm = pattern.asm(segment)
                if(asm!=null)
                    result.add(AsmFragment(asm, pattern.sequence.size))
            }
        }

        return result
    }

    private fun sameConstantIndexedVarOperation(variable: String, index: Int, ins: Instruction): AsmFragment? {
        return when(ins.opcode) {
            Opcode.SHL_BYTE -> AsmFragment(" asl  $variable+$index", 8)
            Opcode.SHR_BYTE -> AsmFragment(" lsr  $variable+$index", 8)
            Opcode.SHL_WORD -> AsmFragment(" asl  $variable+$index |  rol  $variable+${index+1}", 8)
            Opcode.SHR_WORD -> AsmFragment(" lsr  $variable+${index+1},x |  ror  $variable+$index", 8)
            Opcode.ROL_BYTE -> AsmFragment(" rol  $variable+$index", 8)
            Opcode.ROR_BYTE -> AsmFragment(" ror  $variable+$index", 8)
            Opcode.ROL_WORD -> AsmFragment(" rol  $variable+$index |  rol  $variable+${index+1}", 8)
            Opcode.ROR_WORD -> AsmFragment(" ror  $variable+${index+1} |  ror  $variable+$index", 8)
            Opcode.ROL2_BYTE -> AsmFragment(" lda  $variable+$index |  cmp  #\$80 |  rol  $variable+$index", 8)
            Opcode.ROR2_BYTE -> AsmFragment(" lda  $variable+$index |  lsr  a |  bcc  + |  ora  #\$80 |+ |  sta  $variable+$index", 10)
            Opcode.ROL2_WORD -> AsmFragment(" asl  $variable+$index |  rol  $variable+${index+1} |  bcc  + |  inc  $variable+$index |+",20)
            Opcode.ROR2_WORD -> AsmFragment(" lsr  $variable+${index+1} |  ror  $variable+$index |  bcc  + |  lda  $variable+${index+1} |  ora  #\$80 |  sta  $variable+${index+1} |+", 30)
            else -> null
        }
    }

    private fun sameIndexedVarOperation(variable: String, indexVar: String, ins: Instruction): AsmFragment? {
        val saveX = " stx  ${C64Zeropage.SCRATCH_B1} |"         // todo optimize to TXA when possible
        val restoreX = " | ldx  ${C64Zeropage.SCRATCH_B1}"
        val loadXWord: String
        val loadX: String

        when(indexVar) {
            "X" -> {
                loadX = ""
                loadXWord = " txa |  asl a |  tax |"
            }
            "Y" -> {
                loadX = " tya |  tax |"
                loadXWord = " tya |  asl a |  tax |"
            }
            "A" -> {
                loadX = " tax |"
                loadXWord = " asl a |  tax |"
            }
            "AX", "AY", "XY" -> throw AssemblyError("cannot index with word/registerpair")
            else -> {
                // the indexvar is a real variable, not a register
                loadX = " ldx  $indexVar |"
                loadXWord = " lda  $indexVar |  asl  a |  tax |"
            }
        }

        return when (ins.opcode) {
            Opcode.SHL_BYTE -> AsmFragment("$saveX $loadX  asl  $variable,x  $restoreX", 10)
            Opcode.SHR_BYTE -> AsmFragment("$saveX $loadX  lsr  $variable,x  $restoreX", 10)
            Opcode.SHL_WORD -> AsmFragment("$saveX $loadXWord  asl  $variable,x |  rol  $variable+1,x  $restoreX", 10)
            Opcode.SHR_WORD -> AsmFragment("$saveX $loadXWord  lsr  $variable+1,x |  ror  $variable,x  $restoreX", 10)
            Opcode.ROL_BYTE -> AsmFragment("$saveX $loadX  rol  $variable,x  $restoreX", 10)
            Opcode.ROR_BYTE -> AsmFragment("$saveX $loadX  ror  $variable,x  $restoreX", 10)
            Opcode.ROL_WORD -> AsmFragment("$saveX $loadXWord  rol  $variable,x |  rol  $variable+1,x  $restoreX", 10)
            Opcode.ROR_WORD -> AsmFragment("$saveX $loadXWord  ror  $variable+1,x |  ror  $variable,x  $restoreX", 10)
            Opcode.ROL2_BYTE -> AsmFragment("$saveX $loadX  lda  $variable,x |  cmp  #\$80 |  rol  $variable,x  $restoreX", 10)
            Opcode.ROR2_BYTE -> AsmFragment("$saveX $loadX  lda  $variable,x |  lsr  a |  bcc  + |  ora  #\$80 |+ |  sta  $variable,x  $restoreX", 10)
            Opcode.ROL2_WORD -> AsmFragment("$saveX $loadXWord  asl  $variable,x |  rol  $variable+1,x |  bcc  + |  inc  $variable,x  |+  $restoreX", 30)
            Opcode.ROR2_WORD -> AsmFragment("$saveX $loadXWord  lsr  $variable+1,x |  ror  $variable,x |  bcc  + |  lda  $variable+1,x |  ora  #\$80 |  sta  $variable+1,x |+  $restoreX", 30)
            else -> null
        }
    }

    private fun sameMemOperation(address: Int, ins: Instruction): AsmFragment? {
        val addr = address.toHex()
        val addrHi = (address+1).toHex()
        return when(ins.opcode) {
            Opcode.SHL_BYTE -> AsmFragment(" asl  $addr", 10)
            Opcode.SHR_BYTE -> AsmFragment(" lsr  $addr", 10)
            Opcode.SHL_WORD -> AsmFragment(" asl  $addr |  rol  $addrHi", 10)
            Opcode.SHR_WORD -> AsmFragment(" lsr  $addrHi |  ror  $addr", 10)
            Opcode.ROL_BYTE -> AsmFragment(" rol  $addr", 10)
            Opcode.ROR_BYTE -> AsmFragment(" ror  $addr", 10)
            Opcode.ROL_WORD -> AsmFragment(" rol  $addr |  rol  $addrHi", 10)
            Opcode.ROR_WORD -> AsmFragment(" ror  $addrHi |  ror  $addr", 10)
            Opcode.ROL2_BYTE -> AsmFragment(" lda  $addr |  cmp  #\$80 |  rol  $addr", 10)
            Opcode.ROR2_BYTE -> AsmFragment(" lda  $addr |  lsr  a |  bcc  + |  ora  #\$80 |+ |  sta  $addr", 10)
            Opcode.ROL2_WORD -> AsmFragment(" lda  $addr |  cmp #\$80 |  rol  $addr |  rol  $addrHi", 10)
            Opcode.ROR2_WORD -> AsmFragment(" lsr  $addrHi |  ror  $addr |  bcc  + |  lda  $addrHi |  ora  #$80 |  sta  $addrHi |+", 20)
            else -> null
        }
    }

    private fun sameVarOperation(variable: String, ins: Instruction): AsmFragment? {
        return when(ins.opcode) {
            Opcode.SHL_BYTE -> {
                when (variable) {
                    "A" -> AsmFragment(" asl  a", 10)
                    "X" -> AsmFragment(" txa |  asl  a |  tax", 10)
                    "Y" -> AsmFragment(" tya |  asl  a |  tay", 10)
                    else -> AsmFragment(" asl  $variable", 10)
                }
            }
            Opcode.SHR_BYTE -> {
                when (variable) {
                    "A" -> AsmFragment(" lsr  a", 10)
                    "X" -> AsmFragment(" txa |  lsr  a |  tax", 10)
                    "Y" -> AsmFragment(" tya |  lsr  a |  tay", 10)
                    else -> AsmFragment(" lsr  $variable", 10)
                }
            }
            Opcode.SHL_WORD -> {
                when(variable) {
                    "AX" -> AsmFragment(" asl  a |  tay |  txa |  rol  a |  tax  |  tya ", 10)
                    "AY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1.toHex()} |  asl  a |  rol  ${C64Zeropage.SCRATCH_B1.toHex()} |  ldy  ${C64Zeropage.SCRATCH_B1.toHex()} ", 10)
                    "XY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1.toHex()} |  txa |  asl  a |  rol  ${C64Zeropage.SCRATCH_B1.toHex()} |  ldy  ${C64Zeropage.SCRATCH_B1.toHex()} |  tax", 10)
                    else -> AsmFragment(" asl  $variable |  rol  $variable+1", 10)
                }
            }
            Opcode.SHR_WORD -> {
                when(variable) {
                    "AX" -> AsmFragment(" tay |  txa |  lsr  a |  tax  |  tya |  ror  a", 10)
                    "AY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1.toHex()} |  lsr  ${C64Zeropage.SCRATCH_B1.toHex()} |  ror  a |  ldy  ${C64Zeropage.SCRATCH_B1.toHex()} ", 10)
                    "XY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1.toHex()} |  lsr  ${C64Zeropage.SCRATCH_B1.toHex()} |  txa |  ror a  | tax  | ldy  ${C64Zeropage.SCRATCH_B1.toHex()}", 10)
                    else -> AsmFragment(" lsr  $variable+1 |  ror  $variable", 10)
                }
            }
            Opcode.ROL_BYTE -> {
                when (variable) {
                    "A" -> AsmFragment(" rol  a", 10)
                    "X" -> AsmFragment(" txa |  rol  a |  tax", 10)
                    "Y" -> AsmFragment(" tya |  rol  a |  tay", 10)
                    else -> AsmFragment(" rol  $variable", 10)
                }
            }
            Opcode.ROR_BYTE -> {
                when (variable) {
                    "A" -> AsmFragment(" ror  a", 10)
                    "X" -> AsmFragment(" txa |  ror  a |  tax", 10)
                    "Y" -> AsmFragment(" tya |  ror  a |  tay", 10)
                    else -> AsmFragment(" ror  $variable", 10)
                }
            }
            Opcode.ROL_WORD -> {
                when(variable) {
                    "AX" -> AsmFragment(" rol  a |  tay |  txa |  rol  a |  tax  |  tya ", 10)
                    "AY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1} |  rol  a |  rol  ${C64Zeropage.SCRATCH_B1} |  ldy  ${C64Zeropage.SCRATCH_B1} ", 10)
                    "XY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1} |  txa |  rol  a |  rol  ${C64Zeropage.SCRATCH_B1} |  ldy  ${C64Zeropage.SCRATCH_B1} |  tax", 10)
                    else -> AsmFragment(" rol  $variable |  rol  $variable+1", 10)
                }
            }
            Opcode.ROR_WORD -> {
                when(variable) {
                    "AX" -> AsmFragment(" tay |  txa |  ror  a |  tax  |  tya |  ror  a", 10)
                    "AY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1} |  ror  ${C64Zeropage.SCRATCH_B1} |  ror  a |  ldy  ${C64Zeropage.SCRATCH_B1} ", 10)
                    "XY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1} |  ror  ${C64Zeropage.SCRATCH_B1} |  txa |  ror a  | tax  | ldy  ${C64Zeropage.SCRATCH_B1}", 10)
                    else -> AsmFragment(" ror  $variable+1 |  ror  $variable", 10)
                }
            }
            Opcode.ROL2_BYTE -> {       // 8-bit rol
                when (variable) {
                    "A" -> AsmFragment(" cmp  #\$80 |  rol  a", 10)
                    "X" -> AsmFragment(" txa |  cmp  #\$80 |  rol  a |  tax", 10)
                    "Y" -> AsmFragment(" tya |  cmp  #\$80 |  rol  a |  tay", 10)
                    else -> AsmFragment(" lda  $variable |  cmp  #\$80  | rol  $variable", 10)
                }
            }
            Opcode.ROR2_BYTE -> {       // 8-bit ror
                when (variable) {
                    "A" -> AsmFragment(" lsr  a | bcc  + |  ora  #\$80  |+", 10)
                    "X" -> AsmFragment(" txa |  lsr  a |  bcc  + |  ora  #\$80  |+ |  tax", 10)
                    "Y" -> AsmFragment(" tya |  lsr  a |  bcc  + |  ora  #\$80  |+ |  tay", 10)
                    else -> AsmFragment(" lda  $variable |  lsr  a |  bcc  + |  ora  #\$80 |+ |  sta  $variable", 10)
                }
            }
            Opcode.ROL2_WORD -> {
                when(variable) {
                    "AX" -> AsmFragment(" cmp  #\$80 |  rol  a |  tay |  txa |  rol  a |  tax |  tya", 10)
                    "AY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1} |  cmp  #\$80 |  rol  a |  rol  ${C64Zeropage.SCRATCH_B1} |  ldy  ${C64Zeropage.SCRATCH_B1} ", 10)
                    "XY" -> AsmFragment(" sty  ${C64Zeropage.SCRATCH_B1} |  txa |  cmp  #\$80 |  rol  a |  rol  ${C64Zeropage.SCRATCH_B1} |  ldy  ${C64Zeropage.SCRATCH_B1} |  tax", 10)
                    else -> AsmFragment(" lda  $variable |  cmp #\$80 |  rol  $variable |  rol  $variable+1", 10)
                }
            }
            Opcode.ROR2_WORD -> {
                // todo: ror2_word is very slow; it requires a library routine
                when(variable) {
                    "AX" -> AsmFragment(" sta  ${C64Zeropage.SCRATCH_W1} |  stx  ${C64Zeropage.SCRATCH_W1+1}  |  jsr  prog8_lib.ror2_word |  lda  ${C64Zeropage.SCRATCH_W1} |  ldx  ${C64Zeropage.SCRATCH_W1+1}", 20)
                    "AY" -> AsmFragment(" sta  ${C64Zeropage.SCRATCH_W1} |  sty  ${C64Zeropage.SCRATCH_W1+1}  |  jsr  prog8_lib.ror2_word |  lda  ${C64Zeropage.SCRATCH_W1} |  ldy  ${C64Zeropage.SCRATCH_W1+1}", 20)
                    "XY" -> AsmFragment(" stx  ${C64Zeropage.SCRATCH_W1} |  sty  ${C64Zeropage.SCRATCH_W1+1}  |  jsr  prog8_lib.ror2_word |  ldx  ${C64Zeropage.SCRATCH_W1} |  ldy  ${C64Zeropage.SCRATCH_W1+1}", 20)
                    else -> AsmFragment(" lda  $variable |  sta  ${C64Zeropage.SCRATCH_W1} |  lda  $variable+1 |  sta  ${C64Zeropage.SCRATCH_W1+1} |  jsr  prog8_lib.ror2_word |  lda  ${C64Zeropage.SCRATCH_W1} |  sta  $variable |  lda  ${C64Zeropage.SCRATCH_W1+1} |  sta  $variable+1", 30)
                }
            }
//            Opcode.SYSCALL -> {
//                TODO("optimize SYSCALL $ins in-place on variable $variable")
//            }
            else -> null
        }
    }

    private class AsmFragment(val asm: String, var segmentSize: Int=0)

    private class AsmPattern(val sequence: List<Opcode>, val altSequence: List<Opcode>?=null, val asm: (List<Instruction>)->String?)

    private fun loadAFromIndexedByVar(idxVarInstr: Instruction, readArrayInstr: Instruction): String {
        // A =  readArrayInstr [ idxVarInstr ]
        return when (idxVarInstr.callLabel) {
            "A" -> " tay |  lda  ${readArrayInstr.callLabel},y"
            "X" -> " txa |  tay |  lda  ${readArrayInstr.callLabel},y"
            "Y" -> " lda  ${readArrayInstr.callLabel},y"
            else -> " ldy  ${idxVarInstr.callLabel} |  lda  ${readArrayInstr.callLabel},y"
        }
    }

    private fun storeAToIndexedByVar(idxVarInstr: Instruction, writeArrayInstr: Instruction): String {
        // writeArrayInstr [ idxVarInstr ] =  A
        return when (idxVarInstr.callLabel) {
            "A" -> " tay |  sta  ${writeArrayInstr.callLabel},y"
            "X" -> " sta  ${C64Zeropage.SCRATCH_B1} |  ldy  ${C64Zeropage.SCRATCH_B1} |  sta  ${writeArrayInstr.callLabel},y"
            "Y" -> " sta  ${writeArrayInstr.callLabel},y"
            else -> " ldy  ${idxVarInstr.callLabel} |  sta  ${writeArrayInstr.callLabel},y"
        }
    }

    private val patterns = listOf(
            // ----------- push value from array on the stack -------------
//            // push: array[variable]
//            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_BYTE)) { segment ->
//                val loadByteA = loadAFromIndexedByVar(segment[0], segment[1])
//                " $loadByteA |  sta  ${ESTACK_LO.toHex()},x |  dex"
//            },
//            // push: array[mem index]
//            AsmPattern(
//                    listOf(Opcode.PUSH_MEM_B, Opcode.READ_INDEXED_VAR_BYTE),
//                    listOf(Opcode.PUSH_MEM_UB, Opcode.READ_INDEXED_VAR_BYTE)) { segment ->
//                """
//                ldy  ${segment[0].arg!!.integerValue().toHex()}
//                lda  ${segment[1].callLabel},y
//                sta  ${ESTACK_LO.toHex()},x
//                dex
//                """
//            },
//            // ----------- pop value off stack into array -------------
//            // pop into: array[mem index]
//            AsmPattern(listOf(Opcode.PUSH_MEM_UB, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
//                """
//                ldy  ${segment[0].arg!!.integerValue().toHex()}
//                inx
//                lda  ${ESTACK_LO.toHex()},x
//                sta  ${segment[1].callLabel},y
//                """
//            },


            // ----------- assignment to BYTE VARIABLE ----------------
            // var = (u)bytevalue
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.POP_VAR_BYTE)) { segment ->
                when (segment[1].callLabel) {
                    "A", "X", "Y" ->
                        " ld${segment[1].callLabel!!.toLowerCase()}  #${segment[0].arg!!.integerValue().toHex()}"
                    else ->
                        " lda  #${segment[0].arg!!.integerValue().toHex()} |  sta  ${segment[1].callLabel}"
                }
            },
            // var = other var
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.POP_VAR_BYTE)) { segment ->
                when(segment[1].callLabel) {
                    "A" ->
                        when(segment[0].callLabel) {
                            "A" -> null
                            "X" -> "  txa"
                            "Y" -> "  tya"
                            else -> "  lda  ${segment[0].callLabel}"
                        }
                    "X" ->
                        when(segment[0].callLabel) {
                            "A" -> "  tax"
                            "X" -> null
                            "Y" -> "  tya |  tax"
                            else -> "  ldx  ${segment[0].callLabel}"
                        }
                    "Y" ->
                        when(segment[0].callLabel) {
                            "A" -> "  tay"
                            "X" -> "  txa |  tay"
                            "Y" -> null
                            else -> "  ldy  ${segment[0].callLabel}"
                        }
                    else ->
                        when(segment[0].callLabel) {
                            "A" -> "  sta  ${segment[1].callLabel}"
                            "X" -> "  stx  ${segment[1].callLabel}"
                            "Y" -> "  sty  ${segment[1].callLabel}"
                            else -> "  lda  ${segment[0].callLabel} |  sta ${segment[1].callLabel}"
                        }
                }
            },
            // var = mem (u)byte
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.POP_VAR_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.POP_VAR_BYTE)) { segment ->
                when(segment[1].callLabel) {
                    "A", "X", "Y" -> " ld${segment[1].callLabel!!.toLowerCase()}  ${segment[0].arg!!.integerValue().toHex()}"
                    else -> " lda  ${segment[0].arg!!.integerValue().toHex()} |  sta  ${segment[1].callLabel}"
                }
            },
            // var = (u)bytearray[index]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_VAR_BYTE)) { segment ->
                val index = segment[0].arg!!.integerValue()
                when (segment[2].callLabel) {
                    "A", "X", "Y" ->
                        " ld${segment[2].callLabel!!.toLowerCase()}  ${segment[1].callLabel}+$index"
                    else ->
                        " lda  ${segment[1].callLabel}+$index |  sta  ${segment[2].callLabel}"
                }
            },
            // var = (u)bytearray[indexvar]
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_VAR_BYTE)) { segment ->
                val loadByteA = loadAFromIndexedByVar(segment[0], segment[1])
                when(segment[2].callLabel) {
                    "A" -> " $loadByteA"
                    "X" -> " $loadByteA |  tax"
                    "Y" -> " $loadByteA |  tay"
                    else -> " $loadByteA |  sta  ${segment[2].callLabel}"
                }
            },
            // var = (u)bytearray[mem index var]
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_VAR_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_VAR_BYTE)) { segment ->
                val loadByteA = " ldy  ${segment[0].arg!!.integerValue().toHex()} |  lda  ${segment[1].callLabel},y"
                when(segment[2].callLabel) {
                    "A" -> " $loadByteA"
                    "X" -> " $loadByteA |  tax"
                    "Y" -> " $loadByteA |  tay"
                    else -> " $loadByteA |  sta  ${segment[2].callLabel}"
                }
            },


            // ----------- assignment to BYTE MEMORY ----------------
            // mem = (u)byte value
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.POP_MEM_BYTE)) { segment ->
                " lda  #${segment[0].arg!!.integerValue().toHex()} |  sta  ${segment[1].arg!!.integerValue().toHex()}"
            },
            // mem = (u)bytevar
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.POP_MEM_BYTE)) { segment ->
                when(segment[0].callLabel) {
                    "A" -> " sta  ${segment[1].arg!!.integerValue().toHex()}"
                    "X" -> " stx  ${segment[1].arg!!.integerValue().toHex()}"
                    "Y" -> " sty  ${segment[1].arg!!.integerValue().toHex()}"
                    else -> " lda  ${segment[0].callLabel} |  sta  ${segment[1].arg!!.integerValue().toHex()}"
                }
            },
            // mem = mem (u)byte
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.POP_MEM_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.POP_MEM_BYTE)) { segment ->
                " lda  ${segment[0].arg!!.integerValue().toHex()} |  sta  ${segment[1].arg!!.integerValue().toHex()}"
            },
            // mem = (u)bytearray[index]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_MEM_BYTE)) { segment ->
                val address = segment[2].arg!!.integerValue().toHex()
                val index = segment[0].arg!!.integerValue()
                " lda  ${segment[1].callLabel}+$index |  sta  $address"
            },
            // mem = (u)bytearray[indexvar]
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_MEM_BYTE)) { segment->
                val loadByteA = loadAFromIndexedByVar(segment[0], segment[1])
                " $loadByteA |  sta  ${segment[2].arg!!.integerValue().toHex()}"
            },
            // mem = (u)bytearray[mem index var]
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_MEM_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.READ_INDEXED_VAR_BYTE, Opcode.POP_MEM_BYTE)) { segment ->
                """
                ldy  ${segment[0].arg!!.integerValue().toHex()}
                lda  ${segment[1].callLabel},y
                sta  ${segment[2].arg!!.integerValue().toHex()}
                """
            },


            // ----------- assignment to BYTE ARRAY ----------------
            // bytearray[index] = (u)byte value
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val index = segment[1].arg!!.integerValue()
                val value = segment[0].arg!!.integerValue().toHex()
                " lda  #$value |  sta  ${segment[2].callLabel}+$index"
            },
            // bytearray[index] = (u)bytevar
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val index = segment[1].arg!!.integerValue()
                when(segment[0].callLabel) {
                    "A" -> " sta  ${segment[2].callLabel}+$index"
                    "X" -> " stx  ${segment[2].callLabel}+$index"
                    "Y" -> " sty  ${segment[2].callLabel}+$index"
                    else -> " lda  ${segment[0].callLabel} |  sta  ${segment[2].callLabel}+$index"
                }
            },
            // bytearray[index] = mem(u)byte
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val index = segment[1].arg!!.integerValue()
                " lda  ${segment[0].arg!!.integerValue().toHex()} |  sta  ${segment[2].callLabel}+$index"
            },

            // bytearray[index var] = (u)byte value
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val storeA = storeAToIndexedByVar(segment[1], segment[2])
                " lda  #${segment[0].arg!!.integerValue().toHex()} |  $storeA"
            },
            // (u)bytearray[index var] = (u)bytevar
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val storeA = storeAToIndexedByVar(segment[1], segment[2])
                when(segment[0].callLabel) {
                    "A" -> " $storeA"
                    "X" -> " txa |  $storeA"
                    "Y" -> " tya |  $storeA"
                    else -> " lda  ${segment[0].callLabel} |  $storeA"
                }
            },
            // (u)bytearray[index var] = mem (u)byte
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_UB, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE),
                    listOf(Opcode.PUSH_MEM_B, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val storeA = storeAToIndexedByVar(segment[1], segment[2])
                " lda  ${segment[0].arg!!.integerValue().toHex()} |  $storeA"
            },

            // bytearray[index mem] = (u)byte value
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.PUSH_MEM_UB, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                """
                lda  #${segment[0].arg!!.integerValue().toHex()}
                ldy  ${segment[1].arg!!.integerValue().toHex()}
                sta  ${segment[2].callLabel},y
                """
            },
            // bytearray[index mem] = (u)byte var
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.PUSH_MEM_UB, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val loadY = " ldy  ${segment[1].arg!!.integerValue().toHex()}"
                when(segment[0].callLabel) {
                    "A" -> " $loadY |  sta  ${segment[2].callLabel},y"
                    "X" -> " txa |  $loadY |  sta  ${segment[2].callLabel},y"
                    "Y" -> " tya |  $loadY |  sta  ${segment[2].callLabel},y"
                    else -> " lda  ${segment[0].callLabel} |  $loadY |  sta  ${segment[2].callLabel},y"
                }
            },
            // bytearray[index mem] = mem(u)byte
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.PUSH_MEM_UB, Opcode.WRITE_INDEXED_VAR_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.PUSH_MEM_UB, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                """
                ldy  ${segment[1].arg!!.integerValue().toHex()}
                lda  ${segment[0].arg!!.integerValue().toHex()}
                sta  ${segment[2].callLabel},y
                """
            },

            // (u)bytearray2[index2] = (u)bytearray1[index1]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment->
                val index1 = segment[0].arg!!.integerValue()
                val index2 = segment[2].arg!!.integerValue()
                " lda  ${segment[1].callLabel}+$index1 |  sta  ${segment[3].callLabel}+$index2"
            },
            // (u)bytearray2[index2] = (u)bytearray[indexvar]
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val loadByteA = loadAFromIndexedByVar(segment[0], segment[1])
                val index2 = segment[2].arg!!.integerValue()
                " $loadByteA |  sta  ${segment[3].callLabel}+$index2"
            },
            // (u)bytearray[index2] = (u)bytearray[mem ubyte]
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val index2 = segment[2].arg!!.integerValue()
                """
                ldy  ${segment[0].arg!!.integerValue().toHex()}
                lda  ${segment[1].callLabel},y
                sta  ${segment[3].callLabel}+$index2
                """
            },

            // (u)bytearray2[idxvar2] = (u)bytearray1[index1]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val storeA = storeAToIndexedByVar(segment[2], segment[3])
                val index1 = segment[0].arg!!.integerValue()
                " lda  ${segment[1].callLabel}+$index1 |  $storeA"
            },
            // (u)bytearray2[idxvar2] = (u)bytearray1[idxvar]
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val loadA = loadAFromIndexedByVar(segment[0], segment[1])
                val storeA = storeAToIndexedByVar(segment[2], segment[3])
                " $loadA |  $storeA"
            },
            // (u)bytearray2[idxvar2] = (u)bytearray1[mem ubyte]
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_B, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE),
                    listOf(Opcode.PUSH_MEM_UB, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val storeA = storeAToIndexedByVar(segment[2], segment[3])
                " ldy  ${segment[0].arg!!.integerValue().toHex()} |  lda  ${segment[1].callLabel},y |  $storeA"
            },

            // (u)bytearray2[index mem] = (u)bytearray[index]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.PUSH_MEM_UB, Opcode.WRITE_INDEXED_VAR_BYTE)) { segment ->
                val index1 = segment[0].arg!!.integerValue()
                """
                lda  ${segment[1].callLabel}+$index1
                ldy  ${segment[2].arg!!.integerValue().toHex()}
                sta  ${segment[3].callLabel},y
                """
            },



            // ----------- assignment to WORD VARIABLE ----------------
            // var = wordvalue
            AsmPattern(listOf(Opcode.PUSH_WORD, Opcode.POP_VAR_WORD)) { segment ->
                val number = segment[0].arg!!.integerValue().toHex()
                when (segment[1].callLabel) {
                    "AX" -> " lda  #<$number |  ldx  #>$number"
                    "AY" -> " lda  #<$number |  ldy  #>$number"
                    "XY" -> " ldx  #<$number |  ldy  #>$number"
                    else ->
                        """
                        lda  #<${segment[0].arg!!.integerValue().toHex()}
                        sta  ${segment[1].callLabel}
                        lda  #>${segment[0].arg!!.integerValue().toHex()}
                        sta  ${segment[1].callLabel}+1
                        """
                }
            },
            // var = ubytevar
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.UB2UWORD, Opcode.POP_VAR_WORD)) { segment ->
                when(segment[0].callLabel) {
                    "A" -> when(segment[2].callLabel) {
                        "AX" -> " ldx  #0"
                        "AY" -> " ldy  #0"
                        "XY" -> " tax |  ldy  #0"
                        else -> " sta  ${segment[2].callLabel} |  lda  #0 |  sta  ${segment[2].callLabel}+1"
                    }
                    "X" -> when(segment[2].callLabel) {
                        "AX" -> " txa |  ldx  #0"
                        "AY" -> " txa |  ldy  #0"
                        "XY" -> " ldy  #0"
                        else -> " stx  ${segment[2].callLabel} |  lda  #0 |  sta  ${segment[2].callLabel}+1"
                    }
                    "Y" -> when(segment[2].callLabel) {
                        "AX" -> " tya |  ldx  #0"
                        "AY" -> " tya |  ldy  #0"
                        "XY" -> " tya |  tax |  ldy  #0"
                        else -> " sty  ${segment[2].callLabel} |  lda  #0 |  sta  ${segment[2].callLabel}+1"
                    }
                    else ->
                        when(segment[2].callLabel) {
                            "AX" -> " lda  ${segment[0].callLabel} |  ldx  #0"
                            "AY" -> " lda  ${segment[0].callLabel} |  ldy  #0"
                            "XY" -> " ldx  ${segment[0].callLabel} |  ldy  #0"
                            else -> " lda  ${segment[0].callLabel} |  sta  ${segment[2].callLabel} |  lda  #0 |  sta  ${segment[2].callLabel}+1"
                        }
                }
            },
            // var = other var
            AsmPattern(listOf(Opcode.PUSH_VAR_WORD, Opcode.POP_VAR_WORD)) { segment ->
                when(segment[1].callLabel) {
                    "AX" ->
                        when(segment[0].callLabel) {
                            "AX" -> null
                            "AY" -> "  stx  ${C64Zeropage.SCRATCH_B1} |  ldy  ${C64Zeropage.SCRATCH_B1}"
                            "XY" -> "  stx  ${C64Zeropage.SCRATCH_B1} |  tax |  ldy  ${C64Zeropage.SCRATCH_B1}"
                            else -> "  lda  ${segment[0].callLabel} |  ldx  ${segment[0].callLabel}+1"
                        }
                    "AY" ->
                        when(segment[0].callLabel) {
                            "AX" -> "  sty  ${C64Zeropage.SCRATCH_B1} |  ldx  ${C64Zeropage.SCRATCH_B1}"
                            "AY" -> null
                            "XY" -> "  tax"
                            else -> "  lda  ${segment[0].callLabel} |  ldy  ${segment[0].callLabel}+1"
                        }
                    "XY" ->
                        when(segment[0].callLabel) {
                            "AX" -> "  txa |  sty  ${C64Zeropage.SCRATCH_B1} |  ldx  ${C64Zeropage.SCRATCH_B1}"
                            "AY" -> "  txa"
                            "XY" -> null
                            else -> "  ldx  ${segment[0].callLabel} |  ldy  ${segment[0].callLabel}+1"
                        }
                    else ->
                        when(segment[0].callLabel) {
                            "AX" -> "  sta  ${segment[1].callLabel} |  stx  ${segment[1].callLabel}+1"
                            "AY" -> "  sta  ${segment[1].callLabel} |  sty  ${segment[1].callLabel}+1"
                            "XY" -> "  stx  ${segment[1].callLabel} |  sty  ${segment[1].callLabel}+1"
                            else ->
                                """
                                lda  ${segment[0].callLabel}
                                ldy  ${segment[0].callLabel}+1
                                sta  ${segment[1].callLabel}
                                sty  ${segment[1].callLabel}+1
                                """
                        }
                }
            },
            // var = mem ubyte
            AsmPattern(listOf(Opcode.PUSH_MEM_UB, Opcode.UB2UWORD, Opcode.POP_VAR_WORD)) { segment ->
                when(segment[2].callLabel) {
                    "AX" -> " lda  ${segment[0].arg!!.integerValue().toHex()} |  ldx  #0"
                    "AY" -> " lda  ${segment[0].arg!!.integerValue().toHex()} |  ldy  #0"
                    "XY" -> " ldx  ${segment[0].arg!!.integerValue().toHex()} |  ldy  #0"
                    else -> " lda  ${segment[0].arg!!.integerValue().toHex()} |  sta  ${segment[2].callLabel} |  lda  #0 |  sta  ${segment[2].callLabel}+1"
                }
            },
            // var = mem (u)word
            AsmPattern(
                    listOf(Opcode.PUSH_MEM_W, Opcode.POP_VAR_WORD),
                    listOf(Opcode.PUSH_MEM_UW, Opcode.POP_VAR_WORD)) { segment ->
                when(segment[1].callLabel) {
                    "AX" -> " lda  ${segment[0].arg!!.integerValue().toHex()} |  ldx  ${(segment[0].arg!!.integerValue()+1).toHex()}"
                    "AY" -> " lda  ${segment[0].arg!!.integerValue().toHex()} |  ldy  ${(segment[0].arg!!.integerValue()+1).toHex()}"
                    "XY" -> " ldx  ${segment[0].arg!!.integerValue().toHex()} |  ldy  ${(segment[0].arg!!.integerValue()+1).toHex()}"
                    else ->
                        """
                        lda  ${segment[0].arg!!.integerValue().toHex()}
                        sta  ${segment[1].callLabel}
                        lda  ${(segment[0].arg!!.integerValue()+1).toHex()}
                        sta  ${segment[1].callLabel}+1
                        """
                }
            },
            // var = ubytearray[index_byte]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.UB2UWORD, Opcode.POP_VAR_WORD)) { segment ->
                val index = segment[0].arg!!.integerValue().toHex()
                when(segment[3].callLabel) {
                    "AX" -> " lda  ${segment[1].callLabel}+$index |  ldx  #0"
                    "AY" -> " lda  ${segment[1].callLabel}+$index |  ldy  #0"
                    "XY" -> " ldx  ${segment[1].callLabel}+$index |  ldy  #0"
                    else -> " lda  ${segment[1].callLabel}+$index |  sta  ${segment[3].callLabel} |  lda  #0 |  sta  ${segment[3].callLabel}+1"
                }
            },
            // var = (u)wordarray[index_byte]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_WORD, Opcode.POP_VAR_WORD)) { segment ->
                val index = segment[0].arg!!.integerValue()*2
                when(segment[2].callLabel) {
                    "AX" -> " lda  ${segment[1].callLabel}+$index |  ldx  ${segment[1].callLabel}+${index+1}"
                    "AY" -> " lda  ${segment[1].callLabel}+$index |  ldy  ${segment[1].callLabel}+${index+1}"
                    "XY" -> " ldx  ${segment[1].callLabel}+$index |  ldy  ${segment[1].callLabel}+${index+1}"
                    else -> " lda  ${segment[1].callLabel}+$index |  sta  ${segment[2].callLabel} |  lda  ${segment[1].callLabel}+${index+1} |  sta  ${segment[2].callLabel}+1"
                }
            },
            // mem = (u)word value
            AsmPattern(listOf(Opcode.PUSH_WORD, Opcode.POP_MEM_WORD)) { segment ->
                """
                lda  #<${segment[0].arg!!.integerValue().toHex()}
                sta  ${segment[1].arg!!.integerValue().toHex()}
                lda  #>${segment[0].arg!!.integerValue().toHex()}
                sta  ${(segment[1].arg!!.integerValue()+1).toHex()}
                """
            },
            // mem uword = ubyte var
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.UB2UWORD, Opcode.POP_MEM_WORD)) { segment ->
                when(segment[0].callLabel) {
                    "A" -> " sta  ${segment[2].arg!!.integerValue().toHex()} |  lda  #0 |  sta  ${(segment[2].arg!!.integerValue()+1).toHex()}"
                    "X" -> " stx  ${segment[2].arg!!.integerValue().toHex()} |  lda  #0 |  sta  ${(segment[2].arg!!.integerValue()+1).toHex()}"
                    "Y" -> " sty  ${segment[2].arg!!.integerValue().toHex()} |  lda  #0 |  sta  ${(segment[2].arg!!.integerValue()+1).toHex()}"
                    else -> " lda  ${segment[0].callLabel} ||  sta  ${segment[2].arg!!.integerValue().toHex()} |  lda  #0 |  sta  ${(segment[2].arg!!.integerValue()+1).toHex()}"
                }
            },
            // mem uword = mem ubyte
            AsmPattern(listOf(Opcode.PUSH_MEM_UB, Opcode.UB2UWORD, Opcode.POP_MEM_WORD)) { segment ->
                """
                lda  ${segment[0].arg!!.integerValue().toHex()}
                sta  ${segment[2].arg!!.integerValue().toHex()}
                lda  #0
                sta  ${(segment[2].arg!!.integerValue()+1).toHex()}
                """
            },
            // mem uword = uword var
            AsmPattern(listOf(Opcode.PUSH_VAR_WORD, Opcode.POP_MEM_WORD)) { segment ->
                when(segment[0].callLabel) {
                    "AX" -> " sta  ${segment[1].arg!!.integerValue().toHex()} |  stx  ${(segment[1].arg!!.integerValue()+1).toHex()}"
                    "AY" -> " sta  ${segment[1].arg!!.integerValue().toHex()} |  sty  ${(segment[1].arg!!.integerValue()+1).toHex()}"
                    "XY" -> " stx  ${segment[1].arg!!.integerValue().toHex()} |  sty  ${(segment[1].arg!!.integerValue()+1).toHex()}"
                    else -> " lda  ${segment[0].callLabel} ||  sta  ${segment[1].arg!!.integerValue().toHex()} |  lda  ${segment[0].callLabel}+1 |  sta  ${(segment[1].arg!!.integerValue()+1).toHex()}"
                }
            },
            // mem uword = mem uword
            AsmPattern(listOf(Opcode.PUSH_MEM_UW, Opcode.POP_MEM_WORD)) { segment ->
                """
                lda  ${segment[0].arg!!.integerValue().toHex()}
                ldy  ${(segment[0].arg!!.integerValue()+1).toHex()}
                sta  ${segment[1].arg!!.integerValue().toHex()}
                sty  ${(segment[1].arg!!.integerValue()+1).toHex()}
                """
            },
            // mem uword = ubytearray[index]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.UB2UWORD, Opcode.POP_MEM_WORD)) { segment ->
                val index = segment[0].arg!!.integerValue()
                when(segment[1].callLabel) {
                    "AX" -> " sta  ${C64Zeropage.SCRATCH_W1} |  stx  ${C64Zeropage.SCRATCH_W1+1} |  ldy  #$index |  lda  (${C64Zeropage.SCRATCH_W1}),y |  sta  ${segment[3].arg!!.integerValue().toHex()} |  lda  #0 |  sta  ${(segment[3].arg!!.integerValue()+1).toHex()}"
                    "AY" -> " sta  ${C64Zeropage.SCRATCH_W1} |  sty  ${C64Zeropage.SCRATCH_W1+1} |  ldy  #$index |  lda  (${C64Zeropage.SCRATCH_W1}),y |  sta  ${segment[3].arg!!.integerValue().toHex()} |  lda  #0 |  sta  ${(segment[3].arg!!.integerValue()+1).toHex()}"
                    "XY" -> " stx  ${C64Zeropage.SCRATCH_W1} |  sty  ${C64Zeropage.SCRATCH_W1+1} |  ldy  #$index |  lda  (${C64Zeropage.SCRATCH_W1}),y |  sta  ${segment[3].arg!!.integerValue().toHex()} |  lda  #0 |  sta  ${(segment[3].arg!!.integerValue()+1).toHex()}"
                    else ->
                        """
                        lda  ${segment[1].callLabel}+$index
                        ldy  ${segment[1].callLabel}+${index+1}
                        sta  ${segment[3].arg!!.integerValue().toHex()}
                        sty  ${(segment[3].arg!!.integerValue()+1).toHex()}
                        """
                }
            },
            // mem uword = (u)wordarray[indexvalue]
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_WORD, Opcode.POP_MEM_WORD)) { segment ->
                val index = segment[0].arg!!.integerValue()*2
                """
                lda  ${segment[1].callLabel}+$index
                ldy  ${segment[1].callLabel}+1+$index
                sta  ${segment[2].arg!!.integerValue().toHex()}
                sty  ${(segment[2].arg!!.integerValue()+1).toHex()}
                """
            },
            // word var = bytevar (sign extended)
            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.B2WORD, Opcode.POP_VAR_WORD)) { segment ->
                """
                lda  ${segment[0].callLabel}
                sta  ${segment[2].callLabel}
                ora  #$7f
                bmi  +
                lda  #0
+               sta  ${segment[2].callLabel}+1
                """
            },
            // var = membyte (sign extended)
            AsmPattern(listOf(Opcode.PUSH_MEM_B, Opcode.B2WORD, Opcode.POP_VAR_WORD)) { segment ->
                """
                lda  ${segment[0].arg!!.integerValue().toHex()}
                sta  ${segment[2].callLabel}
                ora  #$7f
                bmi  +
                lda  #0
+               sta  ${segment[2].callLabel}+1
                """
            },
            // var = bytearray[index_byte]  (sign extended)
            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.B2WORD, Opcode.POP_VAR_WORD)) { segment ->
                val index = segment[0].arg!!.integerValue().toHex()
                TODO("$segment (sign extended)")
            },





//            // var = bytearray[indexvar]   (sign extended)    VIA REGULAR STACK EVALUATION:  Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_BYTE, Opcode.UB2UWORD, Opcode.POP_VAR_WORD
//            // TODO: UB2UWORD + POP_VAR_WORD special pattern?
//            // var = (u)wordarray[indexvar]   VIA REGULAR STACK EVALUATION:  Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_WORD, Opcode.POP_VAR_WORD





            // ----------- assignment to FLOAT VARIABLE ----------------
            // floatvar = float value
            AsmPattern(listOf(Opcode.PUSH_FLOAT, Opcode.POP_VAR_FLOAT)) { segment ->
                val floatConst = getFloatConst(segment[0].arg!!)
                """
                lda  #<$floatConst
                ldy  #>$floatConst
                sta  ${C64Zeropage.SCRATCH_W1}
                sty  ${C64Zeropage.SCRATCH_W1+1}
                lda  #<${segment[1].callLabel}
                ldy  #>${segment[1].callLabel}
                jsr  prog8_lib.copy_float
                """
            }

//            // floatvar = mem float
//            AsmPattern(listOf(Opcode.PUSH_MEM_FLOAT, Opcode.POP_VAR_FLOAT)) { segment ->
//                """
//                lda  #<${segment[0].arg!!.integerValue().toHex()}
//                ldy  #>${segment[0].arg!!.integerValue().toHex()}
//                sta  ${C64Zeropage.SCRATCH_W1}
//                sty  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<${segment[1].callLabel}
//                ldy  #>${segment[1].callLabel}
//                jsr  prog8_lib.copy_float
//                """
//            },
//            // floatvar  = ubytevar
//            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.UB2FLOAT, Opcode.POP_VAR_FLOAT)) { segment->
//                val loadByteA = when(segment[0].callLabel) {
//                    "A" -> ""
//                    "X" -> "txa"
//                    "Y" -> "tya"
//                    else -> "lda  ${segment[0].callLabel}"
//                }
//                """
//                $loadByteA
//                sta  ${C64Zeropage.SCRATCH_B1}
//                lda  #<${segment[2].callLabel}
//                ldy  #>${segment[2].callLabel}
//                jsr  prog8_lib.ub2float
//                """
//            },
//            // floatvar = bytevar
//            AsmPattern(listOf(Opcode.PUSH_VAR_BYTE, Opcode.B2FLOAT, Opcode.POP_VAR_FLOAT)) { segment->
//                val loadByteA = when(segment[0].callLabel) {
//                    "A" -> ""
//                    "X" -> "txa"
//                    "Y" -> "tya"
//                    else -> "lda  ${segment[0].callLabel}"
//                }
//                """
//                $loadByteA
//                sta  ${C64Zeropage.SCRATCH_B1}
//                lda  #<${segment[2].callLabel}
//                ldy  #>${segment[2].callLabel}
//                jsr  prog8_lib.b2float
//                """
//            },
//            // floatvar = uwordvar
//            AsmPattern(listOf(Opcode.PUSH_VAR_WORD, Opcode.UW2FLOAT, Opcode.POP_VAR_FLOAT)) { segment->
//                when (segment[0].callLabel) {
//                    "AX" ->
//                        """
//                        sta  ${C64Zeropage.SCRATCH_W1}
//                        stx  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.uw2float
//                        """
//                    "AY" ->
//                        """
//                        sta  ${C64Zeropage.SCRATCH_W1}
//                        sty  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.uw2float
//                        """
//                    "XY" ->
//                        """
//                        stx  ${C64Zeropage.SCRATCH_W1}
//                        sty  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.uw2float
//                        """
//                    else ->
//                        """
//                        lda  ${segment[0].callLabel}
//                        sta  ${C64Zeropage.SCRATCH_W1}
//                        lda  ${segment[0].callLabel}+1
//                        sta  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.uw2float
//                        """
//                }
//            },
//            // floatvar = wordvar
//            AsmPattern(listOf(Opcode.PUSH_VAR_WORD, Opcode.W2FLOAT, Opcode.POP_VAR_FLOAT)) { segment ->
//                when (segment[0].callLabel) {
//                    "AX" ->
//                        """
//                        sta  ${C64Zeropage.SCRATCH_W1}
//                        stx  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.w2float
//                        """
//                    "AY" ->
//                        """
//                        sta  ${C64Zeropage.SCRATCH_W1}
//                        sty  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.w2float
//                        """
//                    "XY" ->
//                        """
//                        stx  ${C64Zeropage.SCRATCH_W1}
//                        sty  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.w2float
//                        """
//                    else ->
//                        """
//                        lda  ${segment[0].callLabel}
//                        sta  ${C64Zeropage.SCRATCH_W1}
//                        lda  ${segment[0].callLabel}+1
//                        sta  ${C64Zeropage.SCRATCH_W1+1}
//                        lda  #<${segment[2].callLabel}
//                        ldy  #>${segment[2].callLabel}
//                        jsr  prog8_lib.w2float
//                        """
//                }
//            },
//            // floatvar = mem byte
//            AsmPattern(listOf(Opcode.PUSH_MEM_B, Opcode.B2FLOAT, Opcode.POP_VAR_FLOAT)) { segment->
//                """
//                lda  ${segment[0].arg!!.integerValue().toHex()}
//                sta  ${C64Zeropage.SCRATCH_B1}
//                lda  #<${segment[2].callLabel}
//                ldy  #>${segment[2].callLabel}
//                jsr  prog8_lib.b2float
//                """
//            },
//            // floatvar = mem ubyte
//            AsmPattern(listOf(Opcode.PUSH_MEM_UB, Opcode.UB2FLOAT, Opcode.POP_VAR_FLOAT)) { segment->
//                """
//                lda  ${segment[0].arg!!.integerValue().toHex()}
//                sta  ${C64Zeropage.SCRATCH_B1}
//                lda  #<${segment[2].callLabel}
//                ldy  #>${segment[2].callLabel}
//                jsr  prog8_lib.ub2float
//                """
//            },
//            // floatvar = mem word
//            AsmPattern(listOf(Opcode.PUSH_MEM_W, Opcode.W2FLOAT, Opcode.POP_VAR_FLOAT)) { segment ->
//                """
//                lda  ${segment[0].arg!!.integerValue().toHex()}
//                sta  ${C64Zeropage.SCRATCH_W1}
//                lda  ${(segment[0].arg!!.integerValue()+1).toHex()}
//                sta  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<${segment[2].callLabel}
//                ldy  #>${segment[2].callLabel}
//                jsr  prog8_lib.w2float
//                """
//            },
//            // floatvar = mem uword
//            AsmPattern(listOf(Opcode.PUSH_MEM_UW, Opcode.UW2FLOAT, Opcode.POP_VAR_FLOAT)) { segment ->
//                """
//                lda  ${segment[0].arg!!.integerValue().toHex()}
//                sta  ${C64Zeropage.SCRATCH_W1}
//                lda  ${(segment[0].arg!!.integerValue()+1).toHex()}
//                sta  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<${segment[2].callLabel}
//                ldy  #>${segment[2].callLabel}
//                jsr  prog8_lib.uw2float
//                """
//            },
//            // floatvar = floatarray[index]
//            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_FLOAT, Opcode.POP_VAR_FLOAT)) { segment ->
//                TODO("$segment")
//            },
//            // floatvar = floatarray[indexvar]    VIA REGULAR STACK EVALUATION FOR NOW:  Opcode.PUSH_VAR_BYTE, Opcode.READ_INDEXED_VAR_FLOAT, Opcode.POP_VAR_FLOAT)   TODO Optimize this in special pattern?
//            // floatvar = floatarray[mem index (u)byte]   VIA REGULAR STACK EVALUATION FOR NOW:  Opcode.PUSH_MEM_[U]B, Opcode.READ_INDEXED_VAR_FLOAT, Opcode.POP_VAR_FLOAT    TODO Optimize this in special pattern?
//
//
//
//            // ----------- assignment to MEMORY FLOAT ----------------
//            // mem = floatvalue
//            AsmPattern(listOf(Opcode.PUSH_FLOAT, Opcode.POP_MEM_FLOAT)) { segment ->
//                val floatConst = getFloatConst(segment[0].arg!!)
//                """
//                lda  #<$floatConst
//                ldy  #>$floatConst
//                sta  ${C64Zeropage.SCRATCH_W1}
//                sty  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<${segment[1].arg!!.integerValue().toHex()}
//                ldy  #>${segment[1].arg!!.integerValue().toHex()}
//                jsr  prog8_lib.copy_float
//                """
//            },
//            // mem = floatvar
//            AsmPattern(listOf(Opcode.PUSH_VAR_FLOAT, Opcode.POP_MEM_FLOAT)) { segment ->
//                """
//                lda  #<${segment[0].callLabel}
//                ldy  #>${segment[0].callLabel}
//                sta  ${C64Zeropage.SCRATCH_W1}
//                sty  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<${segment[1].arg!!.integerValue().toHex()}
//                ldy  #>${segment[1].arg!!.integerValue().toHex()}
//                jsr  prog8_lib.copy_float
//                """
//            }



            // ---- @todo assignment to arrays follow below ----------


//            // assignment: wordarray[idxbyte] = word
//            AsmPattern(listOf(Opcode.PUSH_WORD, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_WORD)) { segment ->
//                val index = segment[1].arg!!.integerValue()*2
//                """
//                lda  #<${segment[0].arg!!.integerValue().toHex()}
//                ldy  #>${segment[0].arg!!.integerValue().toHex()}
//                sta  ${segment[2].callLabel}+$index
//                sty  ${segment[2].callLabel}+${index+1}
//                """
//            },
//
//            // assignment: wordarray[memory (u)byte] = word
//            AsmPattern(
//                    listOf(Opcode.PUSH_WORD, Opcode.PUSH_MEM_B, Opcode.WRITE_INDEXED_VAR_WORD),
//                    listOf(Opcode.PUSH_WORD, Opcode.PUSH_MEM_UB, Opcode.WRITE_INDEXED_VAR_WORD)) { segment ->
//                TODO("$segment")
//            },
//
//            // assignment: wordarray[indexvar] = word
//            AsmPattern(listOf(Opcode.PUSH_WORD, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_WORD)) { segment ->
//                TODO("$segment")
//            },
//            // assignment: wordarray[idxbyte] = wordvar
//            AsmPattern(listOf(Opcode.PUSH_VAR_WORD, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_WORD)) { segment ->
//                val index = segment[1].arg!!.integerValue()*2
//                when(segment[0].callLabel) {
//                    "AX" -> " sta  ${segment[2].callLabel}+$index |  stx  ${segment[2].callLabel}+${index+1}"
//                    "AY" -> " sta  ${segment[2].callLabel}+$index |  sty  ${segment[2].callLabel}+${index+1}"
//                    "XY" -> " stx  ${segment[2].callLabel}+$index |  sty  ${segment[2].callLabel}+${index+1}"
//                    else ->
//                        """
//                        lda  ${segment[0].callLabel}
//                        ldy  ${segment[0].callLabel}+1
//                        sta  ${segment[2].callLabel}+$index
//                        sty  ${segment[2].callLabel}+${index+1}
//                        """
//                }
//            },
//            // assignment: wordarray[indexvar] = wordvar
//            AsmPattern(listOf(Opcode.PUSH_VAR_WORD, Opcode.PUSH_VAR_BYTE, Opcode.WRITE_INDEXED_VAR_WORD)) { segment ->
//                TODO("$segment")
//            },
//            // assignment: wordarray[idxbyte] = mem(u)word
//            AsmPattern(
//                    listOf(Opcode.PUSH_MEM_W, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_WORD),
//                    listOf(Opcode.PUSH_MEM_UW, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_WORD)) { segment ->
//                val index = segment[1].arg!!.integerValue()*2
//                """
//                lda  ${segment[0].arg!!.integerValue().toHex()}
//                ldy  ${(segment[0].arg!!.integerValue()+1).toHex()}
//                sta  ${segment[2].callLabel}+$index
//                sty  ${segment[2].callLabel}+${index+1}
//                """
//            },
//
//
//            // assignment: wordarrayw[index2] = wordarray1[index1]
//            AsmPattern(listOf(Opcode.PUSH_BYTE, Opcode.READ_INDEXED_VAR_WORD, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_WORD)) { segment->
//                val index1 = segment[0].arg!!.integerValue()*2
//                val index2 = segment[2].arg!!.integerValue()*2
//                """
//                lda  ${segment[1].callLabel}+$index1
//                ldy  ${segment[1].callLabel}+${index1+1}
//                sta  ${segment[3].callLabel}+$index2
//                sty  ${segment[3].callLabel}+${index2+1}
//                """
//            },
//
//            // assignment: floatarray[idxbyte] = float
//            AsmPattern(listOf(Opcode.PUSH_FLOAT, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_FLOAT)) { segment ->
//                val floatConst = getFloatConst(segment[0].arg!!)
//                val index = segment[1].arg!!.integerValue() * Mflpt5.MemorySize
//                """
//                lda  #<$floatConst
//                ldy  #>$floatConst
//                sta  ${C64Zeropage.SCRATCH_W1}
//                sty  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<(${segment[2].callLabel}+$index)
//                ldy  #>(${segment[2].callLabel}+$index)
//                jsr  prog8_lib.copy_float
//                """
//            },
//            // assignment: floatarray[idxbyte] = floatvar
//            AsmPattern(listOf(Opcode.PUSH_VAR_FLOAT, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_FLOAT)) { segment ->
//                val index = segment[1].arg!!.integerValue() * Mflpt5.MemorySize
//                """
//                lda  #<${segment[0].callLabel}
//                ldy  #>${segment[0].callLabel}
//                sta  ${C64Zeropage.SCRATCH_W1}
//                sty  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<(${segment[2].callLabel}+$index)
//                ldy  #>(${segment[2].callLabel}+$index)
//                jsr  prog8_lib.copy_float
//                """
//            },
//            // assignment: floatarray[idxbyte] = memfloat
//            AsmPattern(listOf(Opcode.PUSH_MEM_FLOAT, Opcode.PUSH_BYTE, Opcode.WRITE_INDEXED_VAR_FLOAT)) { segment ->
//                val index = segment[1].arg!!.integerValue() * Mflpt5.MemorySize
//                """
//                lda  #<${segment[0].arg!!.integerValue().toHex()}
//                ldy  #>${segment[0].arg!!.integerValue().toHex()}
//                sta  ${C64Zeropage.SCRATCH_W1}
//                sty  ${C64Zeropage.SCRATCH_W1+1}
//                lda  #<(${segment[2].callLabel}+$index)
//                ldy  #>(${segment[2].callLabel}+$index)
//                jsr  prog8_lib.copy_float
//                """
//            }

    )
}
