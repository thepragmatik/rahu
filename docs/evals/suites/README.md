# Suite validation

Both shipped suites (`smoke-v1.json`, `dogfood-alpha-v1.json`) are parsed by
`rahu eval` at run time with the strict SuiteV1 parser (unique ids, prompt xor
turns, rubric/expectedLabel, unknown-key rejection). A suite that does not
parse fails `rahu eval` with a field path before any run.
