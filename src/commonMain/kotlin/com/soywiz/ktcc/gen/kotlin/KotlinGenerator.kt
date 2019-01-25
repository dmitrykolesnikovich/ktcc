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
                line("fun $typeName.copyFrom(src: $typeName): $typeName = this.apply { memcpy(CPointer<Unit>(this.ptr), CPointer<Unit>(src.ptr), $typeSize) }")
                line("fun fixedArrayOf$typeName(size: Int, vararg items: $typeName): CPointer<$typeName> = alloca_zero(size * $typeSize).toCPointer<$typeName>().also { for (n in 0 until items.size) $typeName(it.ptr + n * $typeSize).copyFrom(items[n]) }")
                line("operator fun CPointer<$typeName>.get(index: Int): $typeName = $typeName(this.ptr + index * $typeSize)")
                line("operator fun CPointer<$typeName>.set(index: Int, value: $typeName) = $typeName(this.ptr + index * $typeSize).copyFrom(value)")
                line("fun CPointer<$typeName>.plus(offset: Int, dummy: $typeName? = null): CPointer<$typeName> = CPointer(this.ptr + offset * $typeSize)")
                line("fun CPointer<$typeName>.minus(offset: Int, dummy: $typeName? = null): CPointer<$typeName> = CPointer(this.ptr - offset * $typeSize)")
                line("fun CPointer<$typeName>.minus(other: CPointer<$typeName>, dummy: $typeName? = null) = (this.ptr - other.ptr) / $typeSize")
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
            is VarDeclaration -> {
                if (!it.specifiers.hasTypedef) {
                    for (init in it.parsedList) {
                        val isFunc = init.type is FunctionType
                        val prefix = if (isFunc && isTopLevel) "// " else ""

                        val varType = init.type.resolve()
                        val resolvedVarType = varType.resolve()
                        val name = init.name
                        val varInit2 = init.init
                        val varSize = varType.getSize(parser)
                        val varInit = when {
                            //resolvedVarType is ArrayType && varInit2 != null && varInit2 !is ArrayInitExpr -> ArrayInitExpr(listOf(DesignOptInit(null, varInit2)), init.type) // This seems to be an error on GCC
                            varInit2 == null && resolvedVarType is ArrayType -> ArrayInitExpr(listOf(DesignOptInit(null, IntConstant(0))), init.type)
                            varInit2 != null -> varInit2
                            else -> varInit2
                        }

                        //println("varInit=$varInit : resolvedVarType=$resolvedVarType")
                        val varInitStr = when {
                            varInit != null -> varInit.castTo(resolvedVarType).generate(leftType = resolvedVarType)
                            else -> init.type.defaultValue()
                        }

                        val varInitStr2 = if (resolvedVarType is StructType && varInit !is ArrayInitExpr) "${resolvedVarType.Alloc}().copyFrom($varInitStr)" else varInitStr
                        val varTypeName = resolvedVarType.str()
                        if (name in genFunctionScope.localSymbolsStackAllocNames && varType.requireRefStackAlloc) {
                            line("${prefix}var $name: CPointer<$varTypeName> = alloca($varSize).toCPointer<$varTypeName>().also { it.value = $varInitStr2 }")
                        } else {
                            line("${prefix}var $name: $varTypeName = $varInitStr2")
                        }
                    }
                } else {
                    for (init in it.parsedList) {
                        line("// typealias ${init.name} = ${init.type.resolve().str()}")
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
            if (gen != null) generate(gen())
            line("$keyword@${scope?.name}")
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
        (op == "+=") && l.type is PointerType -> r.castTo(Type.INT)
        (op == "-=") && l.type is PointerType && r.type !is PointerType -> r.castTo(Type.INT)
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
        "+=", "-=" -> if (ltype is BasePointerType) "$ll = $ll.${opName(op)}($rr)" else "$ll $op $rr"
        "*=", "/=", "%=" -> "$ll $op $rr"
        "&=" -> "$ll = $ll and $rr"
        "|=" -> "$ll = $ll or $rr"
        "^=" -> "$ll = $ll xor $rr"

        "&&=" -> "$ll = $ll && $rr"
        "||=" -> "$ll = $ll || $rr"
        "<<=" -> "$ll = $ll shl ($rr).toInt()"
        ">>=" -> "$ll = $ll shr ($rr).toInt()"

        else -> TODO("AssignExpr $op")
    }

    fun opName(op: String) = when (op) {
        "+", "++", "+=" -> "plus"
        "-", "--", "-=" -> "minus"
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
            val res = if (signed) "0" else "0u"
            if (size == 8) "${res}L" else res
        }
        is FloatType -> "0f"
        is DoubleType -> "0.0"
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

    companion object {
        val KotlinSupressions = """@Suppress("MemberVisibilityCanBePrivate", "FunctionName", "CanBeVal", "DoubleNegation", "LocalVariableName", "NAME_SHADOWING", "VARIABLE_WITH_REDUNDANT_INITIALIZER", "RemoveRedundantCallsOfConversionMethods", "EXPERIMENTAL_IS_NOT_ENABLED", "RedundantExplicitType", "RemoveExplicitTypeArguments", "RedundantExplicitType", "unused", "UNCHECKED_CAST", "UNUSED_VARIABLE", "UNUSED_PARAMETER", "NOTHING_TO_INLINE", "PropertyName", "ClassName", "USELESS_CAST", "PrivatePropertyName", "CanBeParameter")"""

        val KotlinCRuntime = buildString {
            appendln("// KTCC RUNTIME ///////////////////////////////////////////////////")
            appendln("/*!!inline*/ class CPointer<T>(val ptr: Int)")

            data class FuncType(val n: Int) {
                val cname = "CFunction$n"
                val kname = "kotlin.reflect.KFunction$n"
                val targsNR = (0 until n).map { "T$it" }.joinToString(", ")
                val targs = ((0 until n).map { "T$it" } + listOf("TR")).joinToString(", ")
                val vargs = ((0 until n).map { "v$it: T$it" }).joinToString(", ")
                val cargs = ((0 until n).map { "v$it" }).joinToString(", ")
            }

            data class KType(val name: String, val size: Int, val load: (addr: String) -> String, val store: (addr: String, value: String) -> String, val default: String = "0", val unsigned: Boolean = false) {
                val dummy = if (unsigned) "dummy: $name = $default, unsignedDummy: Unit = Unit" else "dummy: $name = $default"
            }

            val ktypes = listOf(
                    KType("Byte", 1, { addr -> "lb($addr)" }, { addr, v -> "sb($addr, $v)" }),
                    KType("Short", 2, { addr -> "lh($addr)" }, { addr, v -> "sh($addr, $v)" }),
                    KType("Int", 4, { addr -> "lw($addr)" }, { addr, v -> "sw($addr, $v)" }),
                    KType("Long", 8, { addr -> "ld($addr)" }, { addr, v -> "sd($addr, $v)" }, default = "0L"),

                    KType("UByte", 1, { addr -> "lb($addr).toUByte()" }, { addr, v -> "sb($addr, ($v).toByte())" }, default = "0u", unsigned = true),
                    KType("UShort", 2, { addr -> "lh($addr).toUShort()" }, { addr, v -> "sh($addr, ($v).toShort())" }, default = "0u", unsigned = true),
                    KType("UInt", 4, { addr -> "lw($addr).toUInt()" }, { addr, v -> "sw($addr, ($v).toInt())" }, default = "0u", unsigned = true),
                    KType("ULong", 8, { addr -> "ld($addr).toULong()" }, { addr, v -> "sd($addr, ($v).toLong())" }, default = "0uL", unsigned = true),

                    KType("Float", 4, { addr -> "Float.fromBits(lw($addr))" }, { addr, v -> "sw($addr, ($v).toBits())" }, default = "0f")
            )

            val funcTypes = (0 until 8).map { FuncType(it) }

            for (ft in funcTypes) {
                appendln("/*!!inline*/ class ${ft.cname}<${ft.targs}>(val ptr: Int)")
            }

            appendln("")

            appendln(KotlinSupressions)
            appendln("@UseExperimental(ExperimentalUnsignedTypes::class)")
            appendln("open class Runtime(val REQUESTED_HEAP_SIZE: Int = 0) {")

            appendln(/*language=kotlin*/ """
        val Float.Companion.SIZE_BYTES get() = 4
        val Double.Companion.SIZE_BYTES get() = 8

        infix fun UByte.shr(other: Int) = this.toUInt() shr other
        infix fun UByte.shl(other: Int) = this.toUInt() shl other

        val HEAP_SIZE = if (REQUESTED_HEAP_SIZE <= 0) 16 * 1024 * 1024 else REQUESTED_HEAP_SIZE // 16 MB default
        val HEAP = java.nio.ByteBuffer.allocateDirect(HEAP_SIZE).order(java.nio.ByteOrder.LITTLE_ENDIAN)

        val FUNCTIONS = arrayListOf<kotlin.reflect.KFunction<*>>()

        val POINTER_SIZE = 4

        var STACK_PTR = 512 * 1024 // 0.5 MB
        var HEAP_PTR = STACK_PTR

        fun lb(ptr: Int) = HEAP[ptr]
        fun sb(ptr: Int, value: Byte): Unit = run { HEAP.put(ptr, value) }

        fun lh(ptr: Int): Short = HEAP.getShort(ptr)
        fun sh(ptr: Int, value: Short): Unit = run { HEAP.putShort(ptr, value) }

        fun lw(ptr: Int): Int = HEAP.getInt(ptr)
        fun sw(ptr: Int, value: Int): Unit = run { HEAP.putInt(ptr, value) }

        fun ld(ptr: Int): Long = HEAP.getLong(ptr)
        fun sd(ptr: Int, value: Long): Unit = run { HEAP.putLong(ptr, value) }

        inline fun <T> Int.toCPointer(): CPointer<T> = CPointer(this)
        inline fun <T> CPointer<*>.toCPointer(): CPointer<T> = CPointer(this.ptr)

        fun <T> CPointer<T>.addPtr(offset: Int, elementSize: Int) = CPointer<T>(this.ptr + offset * elementSize)

        fun <T> CPointer<CPointer<T>>.plus(offset: Int, dummy: Unit = Unit) = addPtr<CPointer<T>>(offset, 4)
        fun <T> CPointer<CPointer<T>>.minus(offset: Int, dummy: Unit = Unit) = addPtr<CPointer<T>>(-offset, 4)

        operator fun <T> CPointer<CPointer<T>>.set(offset: Int, value: CPointer<T>) = sw(this.ptr + offset * 4, value.ptr)
        operator fun <T> CPointer<CPointer<T>>.get(offset: Int): CPointer<T> = CPointer(lw(this.ptr + offset * 4))

        fun Boolean.toInt() = if (this) 1 else 0
        fun CPointer<*>.toBool() = ptr != 0

        inline fun Number.toBool() = this.toInt() != 0
        inline fun UByte.toBool() = this.toInt() != 0
        inline fun UShort.toBool() = this.toInt() != 0
        inline fun UInt.toBool() = this.toInt() != 0
        inline fun ULong.toBool() = this.toInt() != 0
        fun Boolean.toBool() = this

        // STACK ALLOC
        inline fun <T> stackFrame(callback: () -> T): T {
            val oldPos = STACK_PTR
            return try { callback() } finally { STACK_PTR = oldPos }
        }
        fun alloca(size: Int): CPointer<Unit> = CPointer<Unit>((STACK_PTR - size).also { STACK_PTR -= size })
        fun alloca_zero(size: Int): CPointer<Unit> = alloca(size).also { memset(it, 0, size) }

        // HEAP ALLOC
        fun malloc(size: Int): CPointer<Unit> = CPointer<Unit>(HEAP_PTR.also { HEAP_PTR += size })
        fun free(ptr: CPointer<*>): Unit = Unit // @TODO

        // I/O
        fun putchar(c: Int): Int = c.also { System.out.print(c.toChar()) }

        fun printf(format: CPointer<Byte>, vararg params: Any?) {
            var paramPos = 0
            val fmt = format.readStringz()
            var n = 0
            while (n < fmt.length) {
                val c = fmt[n++]
                if (c == '%') {
                    val c2 = fmt[n++]
                    when (c2) {
                        'd' -> print((params[paramPos++] as Number).toInt())
                        's' -> {
                            val v = params[paramPos++]
                            if (v is CPointer<*>) {
                                print((v as CPointer<Byte>).readStringz())
                            } else {
                                print(v)
                            }
                        }
                        else -> {
                            print(c)
                            print(c2)
                        }
                    }
                } else {
                    putchar(c.toInt())
                }
            }
        }

        // string/memory
        fun memset(ptr: CPointer<*>, value: Int, num: Int): CPointer<Unit> = (ptr as CPointer<Unit>).also { for (n in 0 until num) sb(ptr.ptr + value, value.toByte()) }
        fun memcpy(dest: CPointer<Unit>, src: CPointer<Unit>, num: Int): CPointer<Unit> {
            for (n in 0 until num) {
                sb(dest.ptr + n, lb(src.ptr + n))
            }
            return dest as CPointer<Unit>
        }
        fun memmove(dest: CPointer<Unit>, src: CPointer<Unit>, num: Int): CPointer<Unit> {
            TODO()
        }

        private val STRINGS = LinkedHashMap<String, CPointer<Byte>>()

        // @TODO: UTF-8?
        fun CPointer<Byte>.readStringz(): String {
            var sb = StringBuilder()
            var pos = this.ptr
            while (true) {
                val c = lb(pos++)
                if (c == 0.toByte()) break
                sb.append(c.toChar())
            }
            return sb.toString()
        }

        val String.ptr: CPointer<Byte> get() = STRINGS.getOrPut(this) {
            val bytes = this.toByteArray(Charsets.UTF_8)
            val ptr = malloc(bytes.size + 1).toCPointer<Byte>()
            val p = ptr.ptr
            for (n in 0 until bytes.size) sb(p + n, bytes[n])
            sb(p + bytes.size, 0)
            ptr
        }

        val Array<String>.ptr: CPointer<CPointer<Byte>> get() {
            val array = this
            val ptr = malloc(POINTER_SIZE * array.size).toCPointer<CPointer<Byte>>()
            for (n in 0 until array.size) {
                sw(ptr.ptr + n * POINTER_SIZE, array[n].ptr.ptr)
            }
            return ptr
        }
    """.trimIndent())
            appendln("")
            for (ktype in ktypes) ktype.apply {
                appendln("operator fun CPointer<$name>.get(offset: Int): $name = ${load("this.ptr + offset * $size")}")
                appendln("operator fun CPointer<$name>.set(offset: Int, value: $name) = ${store("this.ptr + offset * $size", "value")}")
                appendln("var CPointer<$name>.value: $name get() = this[0]; set(value): Unit = run { this[0] = value }")
                appendln("fun CPointer<$name>.plus(offset: Int, $dummy) = addPtr<$name>(offset, $size)")
                appendln("fun CPointer<$name>.minus(offset: Int, $dummy) = addPtr<$name>(-offset, $size)")
                appendln("fun CPointer<$name>.minus(other: CPointer<$name>, $dummy) = (this.ptr - other.ptr) / $size")
                appendln("fun fixedArrayOf$name(size: Int, vararg values: $name): CPointer<$name> = alloca_zero(size * $size).toCPointer<$name>().also { for (n in 0 until values.size) ${store("it.ptr + n * $size", "values[n]")} }")
                appendln("")
            }
            appendln("")
            appendln("val FUNCTION_ADDRS = LinkedHashMap<kotlin.reflect.KFunction<*>, Int>()")
            appendln("")
            for (ft in funcTypes) {
                ft.apply {
                    appendln("operator fun <$targs> $cname<$targs>.invoke($vargs): TR = (FUNCTIONS[this.ptr] as (($targsNR) -> TR)).invoke($cargs)")
                    appendln("val <$targs> $kname<$targs>.cfunc get() = $cname<$targs>(FUNCTION_ADDRS.getOrPut(this) { FUNCTIONS.add(this); FUNCTIONS.size - 1 })")
                }
            }

            appendln("}")
        }
    }
}
