-- Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
-- and the EPL 1.0 (https://h2database.com/html/license.html).
-- Initial Developer: H2 Group
--

-- Multiplication

SELECT CAST(-4294967296 AS BIGINT) * CAST (2147483648 AS BIGINT);
>> -9223372036854775808

SELECT CAST(4294967296 AS BIGINT) * CAST (-2147483648 AS BIGINT);
>> -9223372036854775808

SELECT CAST(-2147483648 AS BIGINT) * CAST (4294967296 AS BIGINT);
>> -9223372036854775808

SELECT CAST(2147483648 AS BIGINT) * CAST (-4294967296 AS BIGINT);
>> -9223372036854775808

SELECT CAST(4294967296 AS BIGINT) * CAST (2147483648 AS BIGINT);
> exception NUMERIC_VALUE_OUT_OF_RANGE_1

SELECT CAST(-4294967296 AS BIGINT) * CAST (-2147483648 AS BIGINT);
> exception NUMERIC_VALUE_OUT_OF_RANGE_1

SELECT CAST(2147483648 AS BIGINT) * CAST (4294967296 AS BIGINT);
> exception NUMERIC_VALUE_OUT_OF_RANGE_1

SELECT CAST(-2147483648 AS BIGINT) * CAST (-4294967296 AS BIGINT);
> exception NUMERIC_VALUE_OUT_OF_RANGE_1

SELECT CAST(-9223372036854775808 AS BIGINT) * CAST(1 AS BIGINT);
>> -9223372036854775808

SELECT CAST(-9223372036854775808 AS BIGINT) * CAST(-1 AS BIGINT);
> exception NUMERIC_VALUE_OUT_OF_RANGE_1

SELECT CAST(1 AS BIGINT) * CAST(-9223372036854775808 AS BIGINT);
>> -9223372036854775808

SELECT CAST(-1 AS BIGINT) * CAST(-9223372036854775808 AS BIGINT);
> exception NUMERIC_VALUE_OUT_OF_RANGE_1

-- Division

SELECT CAST(1 AS BIGINT) / CAST(0 AS BIGINT);
> exception DIVISION_BY_ZERO_1

SELECT CAST(-9223372036854775808 AS BIGINT) / CAST(1 AS BIGINT);
>> -9223372036854775808

SELECT CAST(-9223372036854775808 AS BIGINT) / CAST(-1 AS BIGINT);
> exception NUMERIC_VALUE_OUT_OF_RANGE_1

SELECT 0x1L;
> 1
> -
> 1
> rows: 1

SELECT 0x1234567890abL;
> 20015998341291
> --------------
> 20015998341291
> rows: 1

EXPLAIN VALUES (1L, -2147483648L, 2147483647L, -2147483649L, 2147483648L);
>> VALUES (CAST(1 AS BIGINT), -2147483648, CAST(2147483647 AS BIGINT), -2147483649, 2147483648)
