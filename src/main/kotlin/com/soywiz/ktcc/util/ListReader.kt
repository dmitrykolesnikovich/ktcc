package com.soywiz.ktcc.util

open class ListReader<T>(val items: List<T>, val default: T, var pos: Int = 0) {
    val size get() = items.size
    val eof get() = pos >= size

    fun read(): T {
        if (eof) {
            error("EOF found")
        }
        return items[pos++]
    }

    fun readOutside(): T {
        return items.getOrElse(pos++) { default }
    }

    fun peek(offset: Int = 0): T {
        if (eof) {
            error("EOF found")
        }
        return items[pos + offset]
    }

    fun peekOutside(offset: Int = 0): T {
        return items.getOrElse(pos + offset) { default }
    }

    fun expect(expect: T): T {
        val actual = readOutside()
        if (actual != expect) throw ExpectException("Expected '$expect' but found '$actual'")
        return actual
    }

    fun expect(vararg expect: T) {
        for (e in expect) expect(e)
    }

    fun expectAny(vararg expect: T): T {
        val actual = readOutside()
        if (actual !in expect) throw ExpectException("Expected '$expect' but found '$actual'")
        return actual
    }

    fun tryExpect(expect: T): T? {
        if (peek() == expect) {
            return readOutside()
        } else {
            return null
        }
    }

    inline fun <R : Any> restoreOnNull(callback: () -> R?): R? {
        val oldPos = pos
        val result = callback()
        if (result == null) {
            pos = oldPos
        }
        return result
    }

    inline fun <R : Any> tryBlock(callback: () -> R): R? = tryBlockResult(callback).valueOrNull

    inline fun <R : Any> tryBlockResult(callback: () -> R): ItemOrError<R> {
        val oldPos = pos
        val result: Any = try {
            callback()
        } catch (e: ExpectException) {
            e
        }
        if (result is ExpectException) pos = oldPos
        return ItemOrError(result)
    }

    inline fun <R : Any> tryBlocks(name: String, vararg callbacks: () -> R): R {
        val errors = arrayListOf<Throwable>()
        for (callback in callbacks) {
            val result = tryBlockResult(callback)
            if (!result.isError) {
                return result.value
            } else {
                errors += result.error
            }
        }
        throw ExpectException("Tried to parse $name but failed with $errors")
    }
}

class ExpectException(msg: String) : Exception(msg)

inline class ItemOrError<T>(val _value: Any) {
    val valueOrNull: T? get() = if (!isError) value else null
    val value: T get() = _value as T
    val error: Throwable get() = _value as Throwable
    val isError get() = _value is Throwable
}

fun <T> List<T>.reader(default: T) = ListReader(this, default)