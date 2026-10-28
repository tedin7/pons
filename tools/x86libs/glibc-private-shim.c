/*
 * Provides the four GLIBC_PRIVATE helpers that Debian's x86_64 libm.so.6 imports
 * from libc. Box64 forwards libc to the native arm64 glibc, which does not export
 * them to x86 code, so without this shim the x86 libm cannot be loaded.
 * They only parse the optional payload of nan("...") and return a quiet NaN.
 */
#include <math.h>
#include <stddef.h>

static const char *skip_payload(const char *str, char endc)
{
    while (*str != '\0' && *str != endc &&
           ((*str >= '0' && *str <= '9') || (*str >= 'a' && *str <= 'z') ||
            (*str >= 'A' && *str <= 'Z') || *str == '_'))
        str++;
    return str;
}

#define DEFINE_NAN(name, type, value)                                       \
    type name(const char *str, char **endptr, char endc)                   \
    {                                                                       \
        const char *end = skip_payload(str, endc);                          \
        if (endptr != NULL) *endptr = (char *)end;                          \
        return value;                                                       \
    }

DEFINE_NAN(__strtod_nan, double, __builtin_nan(""))
DEFINE_NAN(__strtof_nan, float, __builtin_nanf(""))
DEFINE_NAN(__strtold_nan, long double, __builtin_nanl(""))
DEFINE_NAN(__strtof128_nan, _Float128, __builtin_nanf128(""))
