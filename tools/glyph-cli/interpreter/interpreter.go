package interpreter

import (
	"fmt"

	"glyph-cli/ast"
	"glyph-cli/project"
)

type environment struct {
	vars map[string]interface{}
}

type state struct {
	records   map[string]*ast.RecordDecl
	functions map[string]*ast.FunctionDecl
}

type recordInstance struct {
	name            string
	fields          map[string]interface{}
	immutableFields map[string]struct{}
}

type returnSignal struct {
	value interface{}
}

func (r *returnSignal) Error() string {
	return "return"
}

// Eval executes the program using the provided resolved symbols.
func Eval(program *ast.Program, symbols *project.Symbols) error {
	if symbols == nil {
		return fmt.Errorf("symbols must not be nil")
	}
	mainFn, ok := symbols.Functions["main"]
	if !ok {
		return fmt.Errorf("main function not found")
	}
	st := &state{
		records:   symbols.Records,
		functions: symbols.Functions,
	}
	if _, err := invokeFunction(mainFn, nil, st); err != nil {
		return err
	}
	return nil
}

func invokeFunction(fn *ast.FunctionDecl, args []interface{}, st *state) (interface{}, error) {
	if fn == nil {
		return nil, fmt.Errorf("attempted to invoke nil function")
	}
	if args == nil {
		args = []interface{}{}
	}
	if len(fn.Params) != len(args) {
		return nil, fmt.Errorf("function %s expects %d argument(s) but received %d", fn.Name, len(fn.Params), len(args))
	}
	env := &environment{vars: make(map[string]interface{})}
	for i, param := range fn.Params {
		env.vars[param.Name] = args[i]
	}
	if err := evalBlock(fn.Body, env, st); err != nil {
		if ret, ok := err.(*returnSignal); ok {
			return ret.value, nil
		}
		return nil, err
	}
	return nil, nil
}

func evalBlock(block *ast.Block, env *environment, st *state) error {
	for _, stmt := range block.Statements {
		switch s := stmt.(type) {
		case *ast.VarDecl:
			val, err := evalExpr(s.Value, env, st)
			if err != nil {
				return err
			}
			env.vars[s.Name] = val
		case *ast.AssignStmt:
			if err := applyAssign(s, env, st); err != nil {
				return err
			}
		case *ast.PrintStmt:
			val, err := evalExpr(s.Expr, env, st)
			if err != nil {
				return err
			}
			fmt.Println(val)
		case *ast.ExprStmt:
			if _, err := evalExpr(s.Expr, env, st); err != nil {
				return err
			}
		case *ast.ReturnStmt:
			var val interface{}
			var err error
			if s.Expr != nil {
				val, err = evalExpr(s.Expr, env, st)
				if err != nil {
					return err
				}
			}
			return &returnSignal{value: val}
		default:
			return fmt.Errorf("unsupported statement %T", s)
		}
	}
	return nil
}

func evalExpr(e ast.Expr, env *environment, st *state) (interface{}, error) {
	switch ex := e.(type) {
	case *ast.IntLiteral:
		return ex.Value, nil
	case *ast.BoolLiteral:
		return ex.Value, nil
	case *ast.NullLiteral:
		return nil, nil
	case *ast.StringLiteral:
		return ex.Value, nil
	case *ast.VarRef:
		val, ok := env.vars[ex.Name]
		if !ok {
			return nil, fmt.Errorf("undefined variable %s", ex.Name)
		}
		return val, nil
	case *ast.RecordLiteral:
		return evalRecordLiteral(ex, env, st)
	case *ast.FieldAccess:
		return evalFieldAccess(ex, env, st)
	case *ast.SafeFieldAccess:
		return evalSafeFieldAccess(ex, env, st)
	case *ast.IndexAccess:
		return evalIndexAccess(ex, env, st)
	case *ast.ArrayAllocExpr:
		return evalArrayAlloc(ex, env, st)
	case *ast.MapAllocExpr:
		return evalMapAlloc(ex, env, st)
	case *ast.MapLiteralExpr:
		return evalMapLiteral(ex, env, st)
	case *ast.IfExpr:
		return evalIf(ex, env, st)
	case *ast.TernaryExpr:
		return evalTernary(ex, env, st)
	case *ast.ElvisExpr:
		left, err := evalExpr(ex.Left, env, st)
		if err != nil {
			return nil, err
		}
		if left != nil {
			return left, nil
		}
		return evalExpr(ex.Right, env, st)
	case *ast.MatchExpr:
		return evalMatch(ex, env, st)
	case *ast.CallExpr:
		return evalCall(ex, env, st)
	case *ast.BinaryOp:
		left, err := evalExpr(ex.Left, env, st)
		if err != nil {
			return nil, err
		}
		right, err := evalExpr(ex.Right, env, st)
		if err != nil {
			return nil, err
		}
		lv, lok := left.(int64)
		rv, rok := right.(int64)
		if !lok || !rok {
			return nil, fmt.Errorf("binary op %s expects ints", ex.Op)
		}
		switch ex.Op {
		case "+":
			return lv + rv, nil
		case "-":
			return lv - rv, nil
		case "*":
			return lv * rv, nil
		case "/":
			if rv == 0 {
				return nil, fmt.Errorf("division by zero")
			}
			return lv / rv, nil
		default:
			return nil, fmt.Errorf("unknown operator %s", ex.Op)
		}
	default:
		return nil, fmt.Errorf("unsupported expression %T", ex)
	}
}

func applyAssign(stmt *ast.AssignStmt, env *environment, st *state) error {
	switch target := stmt.Target.(type) {
	case *ast.VarRef:
		val, err := evalExpr(stmt.Value, env, st)
		if err != nil {
			return err
		}
		env.vars[target.Name] = val
		return nil
	case *ast.FieldAccess:
		obj, err := evalExpr(target.Target, env, st)
		if err != nil {
			return err
		}
		rec, ok := obj.(*recordInstance)
		if !ok {
			return fmt.Errorf("field assignment on non-record")
		}
		if _, imm := rec.immutableFields[target.Field]; imm {
			return fmt.Errorf("field %s is immutable", target.Field)
		}
		val, err := evalExpr(stmt.Value, env, st)
		if err != nil {
			return err
		}
		rec.fields[target.Field] = val
		return nil
	case *ast.IndexAccess:
		container, err := evalExpr(target.Target, env, st)
		if err != nil {
			return err
		}
		index, err := evalExpr(target.Index, env, st)
		if err != nil {
			return err
		}
		val, err := evalExpr(stmt.Value, env, st)
		if err != nil {
			return err
		}
		switch c := container.(type) {
		case []interface{}:
			i := int(index.(int64))
			c[i] = val
			return nil
		case map[interface{}]interface{}:
			c[index] = val
			return nil
		default:
			return fmt.Errorf("index assignment on non-collection")
		}
	default:
		return fmt.Errorf("invalid assignment target")
	}
}

func evalRecordLiteral(expr *ast.RecordLiteral, env *environment, st *state) (interface{}, error) {
	rec, ok := st.records[expr.TypeName]
	if !ok {
		return nil, fmt.Errorf("unknown record %s", expr.TypeName)
	}
	fields := make(map[string]interface{}, len(rec.Fields))
	immutable := make(map[string]struct{})
	for _, field := range rec.Fields {
		valExpr, ok := expr.Fields[field.Name]
		if !ok {
			return nil, fmt.Errorf("missing field %s", field.Name)
		}
		val, err := evalExpr(valExpr, env, st)
		if err != nil {
			return nil, err
		}
		fields[field.Name] = val
		if field.Mutability == "val" {
			immutable[field.Name] = struct{}{}
		}
	}
	return &recordInstance{name: rec.Name, fields: fields, immutableFields: immutable}, nil
}

func evalFieldAccess(expr *ast.FieldAccess, env *environment, st *state) (interface{}, error) {
	target, err := evalExpr(expr.Target, env, st)
	if err != nil {
		return nil, err
	}
	rec, ok := target.(*recordInstance)
	if !ok {
		return nil, fmt.Errorf("field access on non-record")
	}
	return rec.fields[expr.Field], nil
}

func evalSafeFieldAccess(expr *ast.SafeFieldAccess, env *environment, st *state) (interface{}, error) {
	target, err := evalExpr(expr.Target, env, st)
	if err != nil {
		return nil, err
	}
	if target == nil {
		return nil, nil
	}
	rec, ok := target.(*recordInstance)
	if !ok {
		return nil, fmt.Errorf("safe field access on non-record")
	}
	return rec.fields[expr.Field], nil
}

func evalIndexAccess(expr *ast.IndexAccess, env *environment, st *state) (interface{}, error) {
	target, err := evalExpr(expr.Target, env, st)
	if err != nil {
		return nil, err
	}
	index, err := evalExpr(expr.Index, env, st)
	if err != nil {
		return nil, err
	}
	switch c := target.(type) {
	case []interface{}:
		return c[int(index.(int64))], nil
	case map[interface{}]interface{}:
		return c[index], nil
	default:
		return nil, fmt.Errorf("index access on non-collection")
	}
}

func evalArrayAlloc(expr *ast.ArrayAllocExpr, env *environment, st *state) (interface{}, error) {
	sizeVal, err := evalExpr(expr.Size, env, st)
	if err != nil {
		return nil, err
	}
	size := int(sizeVal.(int64))
	out := make([]interface{}, size)
	return out, nil
}

func evalMapAlloc(expr *ast.MapAllocExpr, env *environment, st *state) (interface{}, error) {
	if _, err := evalExpr(expr.Capacity, env, st); err != nil {
		return nil, err
	}
	return map[interface{}]interface{}{}, nil
}

func evalMapLiteral(expr *ast.MapLiteralExpr, env *environment, st *state) (interface{}, error) {
	out := map[interface{}]interface{}{}
	for _, entry := range expr.Entries {
		key, err := evalExpr(entry.Key, env, st)
		if err != nil {
			return nil, err
		}
		val, err := evalExpr(entry.Value, env, st)
		if err != nil {
			return nil, err
		}
		out[key] = val
	}
	return out, nil
}

func evalIf(expr *ast.IfExpr, env *environment, st *state) (interface{}, error) {
	condVal, err := evalExpr(expr.Condition, env, st)
	if err != nil {
		return nil, err
	}
	cond, ok := condVal.(bool)
	if !ok {
		return nil, fmt.Errorf("if condition must be bool")
	}
	if cond {
		return evalBlockValue(expr.ThenBlock, env, st)
	}
	if expr.ElseBlock != nil {
		return evalBlockValue(expr.ElseBlock, env, st)
	}
	return nil, nil
}

func evalTernary(expr *ast.TernaryExpr, env *environment, st *state) (interface{}, error) {
	condVal, err := evalExpr(expr.Condition, env, st)
	if err != nil {
		return nil, err
	}
	cond, ok := condVal.(bool)
	if !ok {
		return nil, fmt.Errorf("ternary condition must be bool")
	}
	if cond {
		return evalExpr(expr.IfTrue, env, st)
	}
	return evalExpr(expr.IfFalse, env, st)
}

func evalMatch(expr *ast.MatchExpr, env *environment, st *state) (interface{}, error) {
	target, err := evalExpr(expr.Target, env, st)
	if err != nil {
		return nil, err
	}
	for _, c := range expr.Cases {
		key, err := evalExpr(c.Key, env, st)
		if err != nil {
			return nil, err
		}
		if target == key {
			return evalExpr(c.Value, env, st)
		}
	}
	if expr.ElseExpr != nil {
		return evalExpr(expr.ElseExpr, env, st)
	}
	return nil, fmt.Errorf("match expression missing else branch")
}

func evalBlockValue(block *ast.Block, env *environment, st *state) (interface{}, error) {
	local := &environment{vars: make(map[string]interface{})}
	for k, v := range env.vars {
		local.vars[k] = v
	}
	var last interface{}
	for _, stmt := range block.Statements {
		switch s := stmt.(type) {
		case *ast.VarDecl:
			val, err := evalExpr(s.Value, local, st)
			if err != nil {
				return nil, err
			}
			local.vars[s.Name] = val
			last = nil
		case *ast.AssignStmt:
			if err := applyAssign(s, local, st); err != nil {
				return nil, err
			}
			last = nil
		case *ast.PrintStmt:
			val, err := evalExpr(s.Expr, local, st)
			if err != nil {
				return nil, err
			}
			fmt.Println(val)
			last = nil
		case *ast.ExprStmt:
			val, err := evalExpr(s.Expr, local, st)
			if err != nil {
				return nil, err
			}
			last = val
		case *ast.ReturnStmt:
			var val interface{}
			var err error
			if s.Expr != nil {
				val, err = evalExpr(s.Expr, local, st)
				if err != nil {
					return nil, err
				}
			}
			return nil, &returnSignal{value: val}
		default:
			if err := evalBlock(&ast.Block{Statements: []ast.Statement{s}}, local, st); err != nil {
				if ret, ok := err.(*returnSignal); ok {
					return ret.value, nil
				}
				return nil, err
			}
			last = nil
		}
	}
	return last, nil
}

func evalCall(expr *ast.CallExpr, env *environment, st *state) (interface{}, error) {
	fn, ok := st.functions[expr.Callee]
	if !ok {
		return nil, fmt.Errorf("unknown function %s", expr.Callee)
	}
	args := make([]interface{}, len(expr.Arguments))
	for i, argExpr := range expr.Arguments {
		val, err := evalExpr(argExpr, env, st)
		if err != nil {
			return nil, err
		}
		args[i] = val
	}
	return invokeFunction(fn, args, st)
}
