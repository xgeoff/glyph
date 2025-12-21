package main

import (
	"flag"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"

	"glyph-cli/ast"
	"glyph-cli/interpreter"
	"glyph-cli/parser"
	"glyph-cli/project"
)

func main() {
	var sourcePath string
	var rootPath string
	var inlineCode string
	var helpFlag bool
	var helpShort bool
	var runWasm bool

	flag.StringVar(&sourcePath, "file", "", "Path to a Glyph source file")
	flag.StringVar(&rootPath, "root", "", "Project root directory (defaults to the source file directory)")
	flag.StringVar(&inlineCode, "e", "", "Execute inline Glyph code")
	flag.BoolVar(&helpFlag, "help", false, "Show help")
	flag.BoolVar(&helpShort, "h", false, "Show help (short)")
	flag.BoolVar(&runWasm, "run-wasm", false, "Execute a compiled WASM module via wasmtime")
	flag.Parse()

	if helpFlag || helpShort {
		printHelp()
		return
	}

	// Allow positional argument as shorthand.
	if sourcePath == "" && flag.NArg() > 0 {
		sourcePath = flag.Arg(0)
	}

	if inlineCode == "" && sourcePath == "" {
		printHelp()
		os.Exit(1)
	}

	if inlineCode != "" {
		if rootPath == "" {
			cwd, err := os.Getwd()
			if err != nil {
				fail("resolve working directory: %v", err)
			}
			rootPath = cwd
		}
		runInline(inlineCode, rootPath)
		return
	}

	absSource, err := filepath.Abs(sourcePath)
	if err != nil {
		fail("resolve source path: %v", err)
	}
	if rootPath == "" {
		rootPath = filepath.Dir(absSource)
	}
	absRoot, err := filepath.Abs(rootPath)
	if err != nil {
		fail("resolve root path: %v", err)
	}

	if runWasm {
		runWasmModule(absSource)
		return
	}

	execute(absSource, absRoot, nil)
}

func runInline(code string, root string) {
	absRoot, err := filepath.Abs(root)
	if err != nil {
		fail("resolve root path: %v", err)
	}
	program, err := parser.ParseProgramSource("<inline>", code)
	if err != nil {
		fail("parse error: %v", err)
	}
	execute("", absRoot, program)
}

func execute(absSource string, absRoot string, override *ast.Program) {
	index, err := project.BuildIndex(absRoot)
	if err != nil {
		fail("failed to index project: %v", err)
	}

	var program *ast.Program
	if override != nil {
		program = override
	} else {
		if absSource == "" {
			fail("no source file provided")
		}
		program = index.Programs[absSource]
		if program == nil {
			program, err = parser.ParseProgramFile(absSource)
			if err != nil {
				fail("parse error: %v", err)
			}
		}
	}

	symbols, err := project.Resolve(program, index)
	if err != nil {
		fail("symbol resolution error: %v", err)
	}

	if err := interpreter.Eval(program, symbols); err != nil {
		fail("runtime error: %v", err)
	}
}

func printHelp() {
	fmt.Println(`Glyph CLI

Usage: glyph-cli [options] [file.gly]

Options:
  --file, -file <path>   Path to a Glyph source file
  --root <dir>           Project root directory (defaults to source directory)
  -e <code>              Execute inline Glyph code snippet
  --run-wasm             Run a compiled WASM module via wasmtime
  --help, -h             Show this help message`)
}

func runWasmModule(path string) {
	abs, err := filepath.Abs(path)
	if err != nil {
		fail("resolve WASM path: %v", err)
	}
	if _, err := os.Stat(abs); err != nil {
		fail("cannot read WASM file: %v", err)
	}
	cmd := exec.Command("wasmtime", abs)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Run(); err != nil {
		if execErr, ok := err.(*exec.Error); ok && execErr.Err == exec.ErrNotFound {
			fail("wasmtime not found in PATH")
		}
		fail("wasmtime execution failed: %v", err)
	}
}

func fail(format string, args ...interface{}) {
	fmt.Fprintf(os.Stderr, "Error: "+format+"\n", args...)
	os.Exit(1)
}
