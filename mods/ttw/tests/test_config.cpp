#include <doctest/doctest.h>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include "game/config.h"
#include "game/log.h"

using namespace ttwds::game;

TEST_CASE("config defaults when empty") {
    Config c = parse_config("");
    CHECK(c.bind == "127.0.0.1");
    CHECK(c.port == 7960);
    CHECK(c.logLevel == LogLevel::Info);
    CHECK(c.warnings.empty());
}

TEST_CASE("config parses sections, comments, whitespace and case") {
    Config c = parse_config(
        "; comment\r\n"
        "# also a comment\n"
        "[server]\n"
        "  BindAddress = 127.0.0.2  \n"
        "PORT=7961\n"
        "\n"
        "[Debug]\n"
        "LogLevel = DEBUG ; trailing comment\n");
    CHECK(c.bind == "127.0.0.2");
    CHECK(c.port == 7961);
    CHECK(c.logLevel == LogLevel::Debug);
    CHECK(c.warnings.empty());
}

TEST_CASE("config keys outside their section are ignored") {
    Config c = parse_config("Port=7999\n[Debug]\nPort=7998\nLogLevel=off\n");
    CHECK(c.port == 7960);
    CHECK(c.logLevel == LogLevel::Off);
}

TEST_CASE("config rejects bad values with warnings") {
    Config c = parse_config("[Server]\nBindAddress=0.0.0.0\nPort=abc\n[Debug]\nLogLevel=loud\n");
    CHECK(c.bind == "127.0.0.1");
    CHECK(c.port == 7960);
    CHECK(c.logLevel == LogLevel::Info);
    CHECK(c.warnings.size() == 3);
    for (const char* p : {"0", "65536", "-1", "7786", "7787", "7788", "8765", "27000", "28000", "79600"}) {
        Config d = parse_config(std::string("[Server]\nPort=") + p + "\n");
        CHECK_MESSAGE(d.port == 7960, p);
        CHECK(d.warnings.size() == 1);
    }
    CHECK(parse_config("[Server]\nPort=65535\n").port == 65535);
    CHECK(parse_config("[Server]\nBindAddress=localhost\n").bind == "127.0.0.1");
}

TEST_CASE("shipped ini parses cleanly") {
    std::ifstream f(INI_PATH);
    REQUIRE(f.good());
    std::stringstream ss;
    ss << f.rdbuf();
    Config c = parse_config(ss.str());
    CHECK(c.bind == "127.0.0.1");
    CHECK(c.port == 7960);
    CHECK(c.logLevel == LogLevel::Info);
    CHECK(c.warnings.empty());
}

TEST_CASE("log level names") {
    CHECK(std::string(log_level_name(LogLevel::Off)) == "off");
    CHECK(std::string(log_level_name(LogLevel::Info)) == "info");
    CHECK(std::string(log_level_name(LogLevel::Debug)) == "debug");
}

TEST_CASE("logger filters by level and writes lines") {
    const std::string path = "ttwds_log_test.log";
    std::remove(path.c_str());
    {
        Logger lg;
        REQUIRE(lg.open(path, LogLevel::Info));
        lg.info("hello %d", 42);
        lg.debug("hidden %s", "x");
        lg.setLevel(LogLevel::Debug);
        lg.debug("shown %s", "y");
        lg.setLevel(LogLevel::Off);
        lg.info("never");
    }
    std::ifstream f(path);
    std::stringstream ss;
    ss << f.rdbuf();
    const std::string s = ss.str();
    CHECK(s.find("[info] hello 42") != std::string::npos);
    CHECK(s.find("hidden") == std::string::npos);
    CHECK(s.find("[debug] shown y") != std::string::npos);
    CHECK(s.find("never") == std::string::npos);
    std::remove(path.c_str());
}

TEST_CASE("logger with level off creates no file") {
    const std::string path = "ttwds_log_off.log";
    std::remove(path.c_str());
    {
        Logger lg;
        lg.open(path, LogLevel::Off);
        lg.info("x");
    }
    std::ifstream f(path);
    CHECK_FALSE(f.good());
}

static std::string slurp(const std::string& path) {
    std::ifstream f(path);
    std::stringstream ss;
    ss << f.rdbuf();
    return ss.str();
}

TEST_CASE("logger rotates the previous log to .1") {
    const std::string path = "ttwds_rot.log";
    std::remove(path.c_str());
    std::remove((path + ".1").c_str());
    {
        Logger a;
        REQUIRE(a.open(path, LogLevel::Info));
        a.info("first run");
    }
    {
        Logger b;
        REQUIRE(b.open(path, LogLevel::Info));
        b.info("second run");
    }
    {
        Logger c;
        REQUIRE(c.open(path, LogLevel::Info));
        c.info("third run");
    }
    CHECK(slurp(path).find("third run") != std::string::npos);
    CHECK(slurp(path).find("second run") == std::string::npos);
    CHECK(slurp(path + ".1").find("second run") != std::string::npos);  // .1 replaced
    CHECK(slurp(path + ".1").find("first run") == std::string::npos);
    std::remove(path.c_str());
    std::remove((path + ".1").c_str());
}

TEST_CASE("logger stops at the size cap with one final line") {
    const std::string path = "ttwds_cap.log";
    std::remove(path.c_str());
    {
        Logger lg;
        lg.setMaxBytes(300);
        REQUIRE(lg.open(path, LogLevel::Info));
        for (int i = 0; i < 50; ++i) lg.info("line %d xxxxxxxxxxxxxxxxxxxx", i);
    }
    const std::string s = slurp(path);
    CHECK(s.size() < 450);
    CHECK(s.find("line 0 ") != std::string::npos);
    CHECK(s.find("line 49") == std::string::npos);
    size_t n = 0;
    for (size_t p = s.find("log size cap reached"); p != std::string::npos; p = s.find("log size cap reached", p + 1)) ++n;
    CHECK(n == 1);
    std::remove(path.c_str());
    std::remove((path + ".1").c_str());
}

TEST_CASE("logger force writes even with level off") {
    const std::string path = "ttwds_force.log";
    std::remove(path.c_str());
    {
        Logger lg;
        lg.open(path, LogLevel::Off);
        lg.info("not this");
        lg.force("refusing: %s", "editor");
    }
    const std::string s = slurp(path);
    CHECK(s.find("refusing: editor") != std::string::npos);
    CHECK(s.find("not this") == std::string::npos);
    std::remove(path.c_str());
    std::remove((path + ".1").c_str());
}
