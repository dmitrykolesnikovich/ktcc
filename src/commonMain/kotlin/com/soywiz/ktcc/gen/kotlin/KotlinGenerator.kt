package com.soywiz.ktcc.gen.kotlin

import com.soywiz.ktcc.gen.*
import com.soywiz.ktcc.parser.*
import com.soywiz.ktcc.transform.*
import com.soywiz.ktcc.types.*
import com.soywiz.ktcc.util.*

class KotlinGenerator : BaseGenerator() {
    //val analyzer = ProgramAnalyzer()
    lateinit var program: Program
    lateinit var fixedSizeArrayTypes: Set<ArrayType>
    val parser get() = program.parser
    val strings get() = parser.strings

    fun ArrayType.typeName() = "Array" + elementType.str().replace("[", "").replace("]", "_").replace("<", "_").replace(">", "_").trimEnd('_')

    var genFunctionScope: GenFunctionScope = GenFunctionScope(null)

    fun generate(program: Program, includeErrorsInSource: Boolean = false) = Indenter {
        this@KotlinGenerator.program = program

        fixedSizeArrayTypes = program.getAllTypes(program.parser).filterIsInstance<ArrayType>().filter { it.numElements != null && it.elementType is ArrayType }.toSet()

        //for (type in fixedSizeArrayTypes) line("// FIXED ARRAY TYPE: $type -> ${type.typeName()}")

        if (includeErrorsInSource) {
            for (msg in program.parser.errors) line("// ERROR: $msg")
            for (msg in program.parser.warnings) line("// WARNING: $msg")
        }
        //analyzer.visit(program)
        line("//ENTRY Program")
        line("//Program.main(arrayOf())")
        //for (str in strings) line("// $str")
        line(KotlinSupressions)
        line("@UseExperimental(ExperimentalUnsignedTypes::class)")
        line("class Program(HEAP_SIZE: Int = 0) : Runtime(HEAP_SIZE)") {
            val mainFunc = program.getFunctionOrNull("main")
            if (mainFunc != null) {
                if (mainFunc.params.isEmpty()) {
                    line("companion object { @JvmStatic fun main(args: Array<String>): Unit = run { Program().main() } }")
                } else {
                    line("companion object { @JvmStatic fun main(args: Array<String>): Unit = run { val rargs = arrayOf(\"program\") + args; Program().apply { main(rargs.size, rargs.ptr) } } }")
                }
                line("")
            }

            for (decl in program.decls) {
                generate(decl, isTopLevel = true)
            }

            if (parser.structTypesByName.isNotEmpty()) {
                line("")
                line("//////////////////")
                line("// C STRUCTURES //")
                line("//////////////////")
                line("")
            }

            for (type in parser.structTypesByName.values) {
                val typeName = type.name
                val typeNameAlloc = "${typeName}Alloc"
                val typeSize = "$typeName.SIZE_BYTES"
                val typeFields = type.fieldsByName.values
                //val params = typeFields.map { it.name + ": " + it.type.str() + " = " + it.type.defaultValue() }
                val params = typeFields.map { it.name + ": " + it.type.str() }
                val fields = typeFields.map { it.name + ": " + it.type.str() }
                val fieldsSet = typeFields.map { "this." + it.name + " = " + it.name }
                line("/*!inline*/ class $typeName(val ptr: Int)") {
                    line("companion object") {
                        line("const val SIZE_BYTES = ${type.size}")
                        for (field in typeFields) {
                            // OFFSET_
                            line("const val ${field.offsetName} = ${field.offset}")
                        }
                    }
                }

                if (params.isNotEmpty()) {
                    line("fun $typeNameAlloc(): $typeName = $typeName(alloca($typeSize).ptr)")
                }
                line("fun $typeNameAlloc(${params.joinToString(", ")}): $typeName = $typeNameAlloc().apply { ${fieldsSet.joinToString("; ")} }")
                line("fun $typeName.copyFrom(src: $typeName): $typeName = this.apply { memcpy(CPointer<Unit>(this.ptr), CPointer<Unit>(src.ptr), $typeName.SIZE_BYTES) }")
                line("fun fixedArrayOf$typeName(size: Int, vararg items: $typeName): CPointer<$typeName> = alloca_zero(size * $typeSize).toCPointer<$typeName>().also { for (n in 0 until items.size) $typeName(it.ptr + n * $typeSize).copyFrom(items[n]) }")
                line("operator fun CPointer<$typeName>.get(index: Int): $typeName = $typeName(this.ptr + index * $typeSize)")
                line("operator fun CPointer<$typeName>.set(index: Int, value: $typeName) = $typeName(this.ptr + index * $typeSize).copyFrom(value)")
                line("var CPointer<$typeName>.value: $typeName get() = this[0]; set(value) = run { this[0] = value }")

                for (field in typeFields) {
                    val ftype = field.type
                    val foffsetName = "$typeName.${field.offsetName}"
                    when (ftype) {
                        is IntType -> {
                            val ftypeSize = ftype.size
                            when (ftypeSize) {
                                4 -> line("var $typeName.${field.name}: ${ftype.str()} get() = lw(ptr + $foffsetName); set(value) = sw(ptr + $foffsetName, value)")
                                else -> line("var $typeName.${field.name}: ${ftype.str()} get() = TODO(\"ftypeSize=$ftypeSize\"); set(value) = TODO()")
                            }
                        }
                        is FloatType -> {
                            line("var $typeName.${field.name}: ${ftype.str()} get() = flw(ptr + $foffsetName); set(value) = fsw(ptr + $foffsetName, value)")
                        }
                        is BasePointerType -> {
                            line("var $typeName.${field.name}: ${ftype.str()} get() = CPointer(lw(ptr + $foffsetName)); set(value) = run { sw(ptr + $foffsetName, value.ptr) }")
                        }
                        else -> line("var $typeName.${field.name}: ${ftype.str()} get() = TODO(\"ftype=$ftype\"); set(value) = TODO(\"ftype=$ftype\")")
                    }
                }
            }

            for (type in fixedSizeArrayTypes.distinctBy { it.typeName() }) { // To prevent CONST * issues
                val typeNumElements = type.numElements ?: 0
                val typeName = type.typeName()
                val elementType = type.elementType
                val elementTypeName = elementType.str()
                val elementSize = elementType.getSize(parser)
                line("/*!inline*/ class $typeName(val ptr: Int)") {
                    line("companion object") {
                        line("const val NUM_ELEMENTS = $typeNumElements")
                        line("const val ELEMENT_SIZE_BYTES = $elementSize")
                        line("const val TOTAL_SIZE_BYTES = /*${typeNumElements * elementSize}*/ (NUM_ELEMENTS * ELEMENT_SIZE_BYTES)")
                    }
                    line("fun addr(index: Int) = ptr + index * ELEMENT_SIZE_BYTES")
                }
                when {
                    elementType is IntType -> line("operator fun $typeName.get(index: Int): $elementTypeName = lw(addr(index))")
                    elementType is BasePointerType && elementType.actsAsPointer -> line("operator fun $typeName.get(index: Int): $elementTypeName = CPointer(addr(index))")
                    else -> line("operator fun $typeName.get(index: Int): $elementTypeName = $elementTypeName(addr(index))")
                }
                when {
                    elementType is IntType -> line("operator fun $typeName.set(index: Int, value: $elementTypeName) = sw(addr(index))")
                    elementType is BasePointerType -> line("operator fun $typeName.set(index: Int, value: $elementTypeName) = memcpy(CPointer(addr(index)), CPointer(value.ptr), $typeName.TOTAL_SIZE_BYTES)")
                    else -> line("operator fun $typeName.set(index: Int, value: $elementTypeName) = $elementTypeName(addr(index)).copyFrom(value)")
                }
                line("fun ${typeName}Alloc(vararg items: $elementTypeName) = $typeName(alloca_zero($typeName.TOTAL_SIZE_BYTES).ptr).also { for (n in 0 until items.size) it[n] = items[n] }")
            }
        }
    }

    val Type.requireRefStackAlloc get() = when (this) {
        is StructType -> false
        else -> true
    }

    class GenFunctionScope(val parent: GenFunctionScope? = null) {
        var localSymbolsStackAllocNames = setOf<String>()
        var localSymbolsStackAlloc = setOf<Id>()
    }

    fun <T> functionScope(callback: () -> T): T {
        val old = genFunctionScope
        genFunctionScope = GenFunctionScope(old)
        try {
            return callback()
        } finally {
            genFunctionScope = old
        }
    }

    fun Indenter.generate(it: Decl, isTopLevel: Boolean): Unit {
        when (it) {
            is FuncDeclaration -> {
                line("fun ${it.name.name}(${it.paramsWithVariadic.joinToString(", ") { generateParam(it) }}): ${it.funcType.retType.resolve().str()} = stackFrame") {
                    functionScope {
                        val func = it.func ?: error("Can't get FunctionScope in function")
                        indent {
                            genFunctionScope.localSymbolsStackAlloc = it.findSymbolsRequiringStackAlloc()
                            genFunctionScope.localSymbolsStackAllocNames = genFunctionScope.localSymbolsStackAlloc.map { it.name }.toSet()
                            val localSymbolsStackAlloc = genFunctionScope.localSymbolsStackAlloc
                            for (symbol in localSymbolsStackAlloc) {
                                line("// Require alloc in stack to get pointer: $symbol")
                            }

                            val assignNames = it.body.getMutatingVariables()

                            for (param in it.params) {
                                val name = param.name.name
                                if (name in assignNames) {
                                    line("var $name = $name // Mutating parameter")
                                }
                            }

                            if (func.hasGoto) {
                                val output = StateMachineLowerer.lower(it.body)
                                for (decl in output.decls) {
                                    generate(decl)
                                }
                                line("$__smLabel = -1")
                                line("__sm@while (true)") {
                                    line("when ($__smLabel)") {
                                        line("-1 -> {")
                                        indent()
                                        for (stm in output.stms) {
                                            generate(stm)
                                        }
                                        unindent()
                                        line("}")
                                    }
                                }
                            } else {
                                for (stm in it.body.stms) {
                                    generate(stm)
                                }
                            }
                        }
                    }
                }
            }
            is VarDeclaration -> {
                if (!it.specifiers.hasTypedef) {
                    val ftype = it.specifiers.toFinalType()
                    for (init in it.parsedList) {
                        val isFunc = init.type is FunctionType
                        val prefix = if (isFunc && isTopLevel) "// " else ""

                        val varType = init.type.resolve()
                        val resolvedVarType = varType.resolve()
                        val name = init.name
                        val varInit = init.init
                        val varSize = varType.getSize(parser)
                        val varInitStr = varInit?.castTo(resolvedVarType)?.generate(leftType = resolvedVarType) ?: init.type.defaultValue()

                        val varInitStr2 = if (resolvedVarType is StructType && varInit !is ArrayInitExpr) "${resolvedVarType.Alloc}().copyFrom($varInitStr)" else varInitStr
                        val varTypeName = resolvedVarType.str()
                        if (name in genFunctionScope.localSymbolsStackAllocNames && varType.requireRefStackAlloc) {
                            line("${prefix}var $name: CPointer<$varTypeName> = alloca($varSize).toCPointer<$varTypeName>().also { it.value = $varInitStr2 }")
                        } else {
                            line("${prefix}var $name: $varTypeName = $varInitStr2")
                        }
                    }
                }
            }
            else -> error("Don't know how to generate decl $it")
        }
    }

    val StructType.Alloc get() = "${this.finalName}Alloc"

    fun Expr.castTo(type: Type?) = if (type != null && this.type.resolve() != type.resolve()) CastExpr(this, type) else this

    fun Type.resolve(): Type = parser.resolve(this)

    fun Type.str(): String {
        val res = this.resolve()
        return when (res) {
            is PointerType -> "CPointer<${res.elementType.str()}>"
            is ArrayType -> {
                if (res.numElements == null || res.elementType !is ArrayType) {
                    "CPointer<${res.elementType.str()}>"
                } else {
                    res.typeName()
                }
            }
            is StructType -> parser.getStructTypeInfo(res.spec).name
            else -> res.toString()
        }
    }

    class BreakScope(val name: String, val kind: Kind, val node: Loop, val parent: BreakScope? = null) {
        enum class Kind {
            WHEN, WHILE
        }
        val level: Int = if (parent != null) parent.level + 1 else 1
        val scopeForContinue: BreakScope? get() = if (kind == Kind.WHILE) this else parent?.scopeForContinue
    }

    private var breakScope: BreakScope? = null

    val breakScopeForContinue: BreakScope? get() = breakScope?.scopeForContinue

    fun <T> breakScope(name: String, kind: BreakScope.Kind, node: Loop, callback: (BreakScope) -> T): T {
        val old = breakScope
        breakScope = BreakScope("$name${breakScope?.level ?: 0}", kind, node, old)
        try {
            return callback(breakScope!!)
        } finally {
            breakScope = old
        }
    }

    private val __smLabel = "__smLabel"
    private val tempContext = TempContext()

    fun Indenter.generate(it: Stm): Unit = when (it) {
        is LowGoto -> {
            line("$__smLabel = ${it.label.id}; continue@__sm")
        }
        is LowLabel -> {
            line("$__smLabel = ${it.label.id}")
            unindent()
            line("}")
            line("${it.label.id} -> {")
            indent()
        }
        is LowIfGoto -> {
            line("if (${it.cond.generate(par = false)}) { $__smLabel = ${it.label.id}; continue@__sm }")
        }
        is LowSwitchGoto -> {
            line("$__smLabel = when (${it.subject.generate(par = false)})") {
                for ((expr, label) in it.map) {
                    if (expr != null) {
                        line("${expr.generate(par = false)} -> ${label.id}")
                    } else {
                        line("else -> ${label.id}")
                    }
                }
            }
            line("continue@__sm")
        }
        is EmptyStm -> Unit
        is Stms -> {
            val hasDeclarations = it.stms.any { it is Decl }
            if (hasDeclarations) {
                lineStackFrame(it) {
                    for (s in it.stms) generate(s)
                }
            } else {
                for (s in it.stms) generate(s)
            }
        }
        is RawStm -> {
            line(it.raw)
        }
        is CommentStm -> {
            if (it.multiline) {
                line("/* ${it.comment} */")
            } else {
                line("// ${it.comment}")
            }
        }
        is Return -> {
            val func = it.func ?: error("Return doesn't have linked a function scope")
            if (it.expr != null) line("return ${(it.expr.castTo(func.rettype)).generate(par = false)}") else line("return")
        }
        is ExprStm -> {
            val expr = it.expr
            if (expr != null) {
                when {
                    expr is AssignExpr -> line(expr.genAssignBase(expr.l.generate(), expr.rightCasted().generate(), expr.l.type.resolve()))
                    expr is BaseUnaryOp && expr.op in setOf("++", "--") -> {
                        val e = expr.operand.generate()
                        line("$e = $e.${opName(expr.op)}(1)")
                    }
                    else -> line(expr.generate(par = false))
                }
            }
            Unit
        }
        is While -> {
            if (it.containsBreakOrContinue()) {
                breakScope("while", BreakScope.Kind.WHILE, it) { scope ->
                    line("${scope.name}@while (${(it.cond).castTo(Type.BOOL).generate(par = false)}) {")
                    indent {
                        generate(it.body)
                    }
                    line("}")
                }
            } else {
                line("while (${(it.cond).castTo(Type.BOOL).generate(par = false)}) {")
                indent {
                    generate(it.body)
                }
                line("}")
            }
        }
        is DoWhile -> {
            breakScope("do", BreakScope.Kind.WHILE, it) { scope ->
                line("${scope.name}@do {")
                indent {
                    generate(it.body)
                }
                line("} while (${(it.cond).castTo(Type.BOOL).generate(par = false)})")
            }
        }
        is For -> generate(it.lower())
        is SwitchWithoutFallthrough -> {
            //breakScope("when", BreakScope.Kind.WHEN) { scope ->
                //line("${scope.name}@when (${it.subject.generate(par = false)})") {
                line("when (${it.subject.generate(par = false)})") {
                    for (stm in it.bodyCases) {
                        when (stm) {
                            is CaseStm -> line("${stm.expr.generate(par = false)} ->") { generate(stm.stm) }
                            is DefaultStm -> line("else ->") { generate(stm.stm) }
                        }
                    }
                }
            //}
        }
        is Switch -> generate(it.removeFallthrough(tempContext))
        // @TODO: Fallthrough!
        is CaseStm -> line("// unexpected outer CASE ${it.expr.generate()}").apply { generate(it.stm) }
        is DefaultStm -> line("// unexpected outer DEFAULT").apply { generate(it.stm) }
        is LabeledStm -> {
            line("${it.id}@run {")
            indent {
                generate(it.stm)
            }
            line("}")
        }
        is Goto -> {
            line("goto@${it.id} /* @TODO: goto must convert the function into a state machine */")
        }
        is Continue, is Break -> {
            val scope = if (it is Continue) breakScopeForContinue else breakScope
            val keyword = if (it is Continue) "continue" else "break"
            val gen = if (it is Continue) scope?.node?.onContinue else scope?.node?.onBreak
            if (gen != null) {
                line("run") {
                    generate(gen())
                    line("$keyword@${scope?.name}")
                }
            } else {
                line("$keyword@${scope?.name}")
            }
        }
        is IfElse -> {
            line("if (${it.cond.castTo(Type.BOOL).generate(par = false)}) {")
            indent {
                generate(it.strue)
            }
            if (it.sfalse != null) {
                line("} else {")
                indent {
                    generate(it.sfalse)
                }
                line("}")
            } else {
                line("}")
            }
        }
        is Decl -> generate(it, isTopLevel = false)
        else -> error("Don't know how to generate stm $it")
    }

    private var oldPosIndex = 0

    private fun Indenter.lineStackFrame(node: Stm, code: () -> Unit) {
        if (node.containsBreakOrContinue()) {
            val oldPos = "__oldPos${oldPosIndex++}"
            line("val $oldPos = STACK_PTR")
            line("try") {
                code()
            }
            line("finally") {
                line("STACK_PTR = $oldPos")
            }
        } else {
            line("stackFrame") {
                code()
            }
        }
    }

    fun generateParam(it: CParamBase): String = when (it) {
        is CParam -> generateParam(it)
        is CParamVariadic -> "vararg __VA__: Any?"
        else -> TODO()
    }
    fun generateParam(it: CParam): String = "${it.name}: ${it.type.resolve().str()}"

    fun ListTypeSpecifier.toKotlinType(): String {
        var void = false
        var static = false
        var unsigned = false
        var integral = false
        var longCount = 0
        var intSize = 4
        var float = false
        for (spec in items) {
            when (spec) {
                is BasicTypeSpecifier -> {
                    when (spec.id) {
                        BasicTypeSpecifier.Kind.VOID -> void = true
                        BasicTypeSpecifier.Kind.INT -> integral = true
                        BasicTypeSpecifier.Kind.CHAR -> {
                            intSize = 1
                            integral = true
                        }
                        BasicTypeSpecifier.Kind.UNSIGNED -> run { unsigned = true; integral = true }
                        BasicTypeSpecifier.Kind.FLOAT -> float = true
                        else -> TODO("${spec.id}")
                    }
                }
                is StorageClassSpecifier -> {
                    when (spec.kind) {
                        StorageClassSpecifier.Kind.STATIC -> static = true
                    }
                }
                is RefTypeSpecifier -> {
                    Unit // @TODO
                }
                is TypeQualifier -> {
                    Unit // @TODO
                }
                else -> TODO("toKotlinType: $spec")
            }
        }
        return when {
            void -> "Unit"
            integral -> when (intSize) {
                1 -> "Byte"
                else -> "Int"
            }
            float -> "Float"
            //else -> TODO("toKotlinType")
            else -> "Unknown"
        }
    }

    fun AssignExpr.rightCasted(): Expr = when {
        (op == "+=" || op == "-=") && l.type is PointerType -> r.castTo(Type.INT)
        else -> r.castTo(l.type)
    }

    fun AssignExpr.genAssignBase(ll: String, rr: String, ltype: Type, rtype: Type = ltype) = when (op) {
        "=" -> {
            //println("genAssignBase: $ll, $rr, $ltype : ${ltype}")
            if (ltype is StructType && rtype is StructType) {
                "$ll.copyFrom($rr)"
            } else {
                "$ll = $rr"
            }
        }
        "+=", "-=", "*=", "/=", "%=" -> "$ll $op $rr"
        "&=" -> "$ll = $ll and $rr"
        "|=" -> "$ll = $ll or $rr"
        "^=" -> "$ll = $ll xor $rr"

        "&&=" -> "$ll = $ll && $rr"
        "||=" -> "$ll = $ll || $rr"
        "<<=" -> "$ll = $ll shl ($rr).toInt()"
        ">>=" -> "$ll = $ll shr ($rr).toInt()"

        else -> TODO("AssignExpr $op")
    }

    private val __tmp = "`$`"

    fun opName(op: String) = when (op) {
        "+", "++" -> "plus"
        "-", "--" -> "minus"
        else -> op
    }

    private val __it = "`\$`"

    fun Id.isGlobalDeclFuncRef() = type is FunctionType && isGlobal && name in program.funcDeclByName

    fun Expr.generate(par: Boolean = true, leftType: Type? = null): String = when (this) {
        is ConstExpr -> this.expr.generate(par = par, leftType = leftType)
        is NumberConstant -> when {
            type is FloatType && (type as FloatType).size == 4 ->  "${nvalue}f"
            else -> "$nvalue"
        }
        is Binop -> {
            val ll = l.castTo(extypeL).generate()
            val rr = r.castTo(extypeR).generate()

            val base = when (op) {
                "+", "-" -> if (l.type is BasePointerType) {
                    "$ll.${opName(op)}($rr)"
                } else {
                    "$ll $op $rr"
                }
                "*", "/", "%" -> "$ll $op $rr"
                "==", "!=", "<", ">", "<=", ">=" -> "$ll $op $rr"
                "&&", "||" -> "$ll $op $rr"
                "^" -> "$ll xor $rr"
                "&" -> "$ll and $rr"
                "|" -> "$ll or $rr"
                "<<" -> "$ll shl ($rr).toInt()"
                ">>" -> "$ll shr ($rr).toInt()"
                else -> TODO("Binop $op")
            }
            if (par) "($base)" else base
        }
        is AssignExpr -> {
            val ll = l.generate(par = false)
            val rr2 = rightCasted().generate()
            val base = genAssignBase(ll, rr2, l.type.resolve())
            val rbase = "run { $base }.let { $ll }"
            if (par) "($rbase)" else rbase
        }
        is Id -> {
            when {
                isGlobalDeclFuncRef() -> "::$name.cfunc"
                name in genFunctionScope.localSymbolsStackAllocNames && this.type.resolve() !is StructType -> "$name.value"
                else -> name
            }
        }
        is PostfixExpr -> {
            val left = lvalue.generate()
            when (op) {
                "++", "--" -> {
                    if (lvalue.type is PointerType) {
                        "$left.also { $left = $left.${opName(op)}(1) }"
                    } else {
                        "$left$op"
                    }
                }
                else -> TODO("Don't know how to generate postfix operator '$op'")
            }
        }
        is CallExpr -> {
            val etype = expr.type.resolve()
            val typeArgs = if (etype is FunctionType) etype.args else listOf()
            val callPart = if (expr is Id && expr.isGlobalDeclFuncRef()) expr.name else expr.generate()
            val argsStr = args.withIndex().map { (index, arg) ->
                val ltype = typeArgs.getOrNull(index)?.type
                arg.castTo(ltype).generate()
            }
            "$callPart(${argsStr.joinToString(", ")})"
        }
        is StringConstant -> "$raw.ptr"
        is CharConstant -> "$raw.toInt()"
        is CastExpr -> {
            val type = this.type.resolve()
            val exprType = expr.type
            val exprResolvedType = exprType.resolve()
            val base = expr.generate(leftType = leftType)
            val rbase = when (exprResolvedType) {
                //is PointerFType -> "$base.ptr"
                is StructType -> "$base.ptr"
                is FunctionType -> "$base.ptr"
                else -> base
            }
            val res = when (type) {
                //is PointerFType -> "$type($base)"
                is StructType -> "${type.finalName}($rbase)"
                is FunctionType -> "${type.typeName}($rbase)"
                else -> "$base.to${type.str()}()"
            }
            if (par) "($res)" else res
        }
        is ArrayAccessExpr -> "${expr.generate()}[${index.castTo(Type.INT).generate(par = false)}]"
        is UnaryExpr -> {
            val e = rvalue.castTo(this.extypeR).generate(par = true, leftType = leftType)
            val res = when (op) {
                "*" -> "($e)[0]"
                "&" -> {
                    // Reference
                    when (rvalue) {
                        is FieldAccessExpr -> "CPointer((" + rvalue.left.generate(par = false) + ").ptr + ${rvalue.structType?.str()}.OFFSET_${rvalue.id.name})"
                        is ArrayAccessExpr -> "((" + rvalue.expr.generate(par = false) + ") + (" +  rvalue.index.generate(par = false) + "))"
                        is Id -> if (type.resolve() is StructType) "${rvalue.name}.ptr" else rvalue.name
                        else -> "&$e /*TODO*/"
                    }

                }
                "-" -> "-$e"
                "+" -> "+$e"
                "!" -> "!$e"
                "~" -> "($e).inv()"
                "++", "--" -> {
                    if (rvalue.type is PointerType) {
                        "$e.${opName(op)}(1).also { $__it -> $e = $__it }"
                    } else {
                        "$op$e"
                    }

                }
                else -> TODO("Don't know how to generate unary operator '$op'")
            }
            if (par) "($res)" else res
        }
        is ArrayInitExpr -> {
            val ltype = leftType?.resolve()
            when (ltype) {
                is StructType -> {
                    val structType = ltype.getProgramType()
                    val structName = structType.name
                    val inits = LinkedHashMap<String, String>()
                    var index = 0
                    for (item in this.items) {
                        val field = structType.fields.getOrNull(index++)
                        if (field != null) {
                            inits[field.name] = item.initializer.generate(leftType = field.type)
                        }
                    }
                    val setFields = structType.fields.associate { it.name to (inits[it.name] ?: it.type.defaultValue()) }
                    "${structName}Alloc(${setFields.map { "${it.key} = ${it.value}" }.joinToString(", ")})"
                }
                is BasePointerType -> {
                    val itemsStr = items.joinToString(", ") { it.initializer.castTo(ltype.elementType).generate() }
                    val numElements = if (ltype is ArrayType) ltype.numElements else null
                    val relements = numElements ?: items.size
                    when {
                        ltype is ArrayType && ltype.hasSubarrays && numElements != null -> "${ltype.str()}Alloc($itemsStr)"
                        else -> {
                            "fixedArrayOf${ltype.elementType.str()}($relements, $itemsStr)"
                        }
                    }
                }
                else -> {
                    "/*not a valid array init type: $ltype */ listOf(" + this.items.joinToString(", ") { it.initializer.generate() } + ")"
                }
            }
        }
        is TenaryExpr -> {
            "(if (${this.cond.castTo(Type.BOOL).generate(par = false)}) ${this.etrue.castTo(this.type).generate()} else ${this.efalse.castTo(this.type).generate()})"
        }
        is FieldAccessExpr -> {
            if (indirect) {
                "${this.left.generate()}.value.${this.id}"
            } else {
                "${this.left.generate()}.${this.id}"
            }
        }
        is CommaExpr -> {
            "run { ${this.exprs.joinToString("; ") { it.generate(par = false) }} }"
        }
        is SizeOfAlignExprBase -> {
            if (this is SizeOfAlignExprExpr && this.expr is StringConstant) {
                val computed = this.expr.value.length + 1
                "$computed"
            } else {
                val ftype = this.ftype.resolve()
                val computedSize = ftype.getSize(parser)
                when (ftype) {
                    is ArrayType -> "$computedSize"
                    else -> "${this.ftype.str()}.SIZE_BYTES"
                }
                //this.kind + "(" + this.ftype +  ")"
            }
        }
        else -> error("Don't know how to generate expr $this (${this::class})")
    }

    val StructType.finalName: String get() = getProgramType()?.name ?: this.spec.id?.name ?: "unknown"
    val FunctionType.typeName: String get() = this.toString()

    fun Type.defaultValue(): String = when (this) {
        is IntType -> {
            val res = if (signed != false) "0" else "0u"
            if (size == 8) "${res}L" else res
        }
        is FloatType -> if (size == 8) "0.0" else "0f"
        is PointerType -> "CPointer(0)"
        is RefType -> this.resolve().defaultValue()
        is StructType -> "${this.getProgramType().name}Alloc()"
        is ArrayType -> "0 /*$this*/"
        is FunctionType -> "0 /*$this*/"
        else -> "0 /*Unknown defaultValue for ${this::class}: $this*/"
    }

    fun StructType.getProgramType() = parser.getStructTypeInfo(this.spec)
    fun Type.getProgramType() = when (this) {
        is StructType -> getProgramType()
        is RefType -> parser.getStructTypeInfo(this.id)
        else -> error("$this")
    }
}