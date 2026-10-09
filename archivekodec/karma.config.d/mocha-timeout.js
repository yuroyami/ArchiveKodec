// Browser test runs get the 30 s per test that build.gradle.kts gives the Node runs; the
// encoder round trips and multi-MiB streaming tests outlast Mocha's 2 s default.
config.client = config.client || {};
config.client.mocha = Object.assign({}, config.client.mocha, { timeout: 30000 });
