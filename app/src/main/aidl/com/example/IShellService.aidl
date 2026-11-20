package com.example;

interface IShellService {
    void destroy() = 16777114;
    String exec(String command) = 2;
    String execBatch(String commandsJson) = 3;
}
