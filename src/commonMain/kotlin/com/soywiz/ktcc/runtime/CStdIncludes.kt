package com.soywiz.ktcc.runtime

val CStdIncludes = mapOf(
"stdint.h" to/* language=c */  """
""",

"stdio.h" to /* language=c */ """
int putchar(int c);
void printf(char *fmt, ...);
""",

"stdlib.h" to /* language=c */ """
""",

"string.h" to /* language=c */ """
"""
)