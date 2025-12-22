package ast

type Program struct {
	Package     *PackageDecl
	Imports     []*ImportDecl
	TypeAliases []*TypeAliasDecl
	Records     []*RecordDecl
	SumTypes    []*SumTypeDecl
	Functions   []*FunctionDecl
}

type PackageDecl struct {
	Name string
}

type ImportDecl struct {
	Name string
}

type TypeAliasDecl struct {
	Name       string
	TargetType string
}

type SumTypeDecl struct {
	Name     string
	Variants []*VariantDecl
}

type VariantDecl struct {
	Name   string
	Fields []*VariantField
}

type VariantField struct {
	Name string
	Type string
}

type FunctionDecl struct {
	Name       string
	Params     []*Param
	ReturnType string
	Body       *Block
}

type Param struct {
	Name string
	Type string
}

type RecordDecl struct {
	Name   string
	Fields []*RecordField
}

type RecordField struct {
	Name       string
	Type       string
	Mutability string // "val" or "var"
}

type Block struct {
	Statements []Statement
}

type Statement interface {
	stmtNode()
}

type VarDecl struct {
	Name       string
	Type       string
	Mutability string // "const", "val", or "var"
	Value      Expr
}

type PrintStmt struct {
	Expr Expr
}

type ExprStmt struct {
	Expr Expr
}

type AssignStmt struct {
	Target Expr
	Value  Expr
}

type ReturnStmt struct {
	Expr Expr
}

type Expr interface {
	exprNode()
}

type IntLiteral struct {
	Value int64
}

type BoolLiteral struct {
	Value bool
}

type NullLiteral struct{}

type StringLiteral struct {
	Value string
}

type BinaryOp struct {
	Op          string
	Left, Right Expr
}

type VarRef struct {
	Name string
}

type IfExpr struct {
	Condition Expr
	ThenBlock *Block
	ElseBlock *Block
}

type TernaryExpr struct {
	Condition Expr
	IfTrue    Expr
	IfFalse   Expr
}

type ElvisExpr struct {
	Left  Expr
	Right Expr
}

type MatchExpr struct {
	Target   Expr
	Cases    []*MatchCase
	ElseExpr Expr
}

type MatchCase struct {
	Pattern Pattern
	Value   Expr
}

type RecordLiteral struct {
	TypeName string
	Fields   map[string]Expr
}

type FieldAccess struct {
	Target Expr
	Field  string
}

type SafeFieldAccess struct {
	Target Expr
	Field  string
}

type IndexAccess struct {
	Target Expr
	Index  Expr
}

type ArrayAllocExpr struct {
	ElementType string
	Size        Expr
}

type MapAllocExpr struct {
	KeyType   string
	ValueType string
	Capacity  Expr
}

type MapLiteralExpr struct {
	KeyType   string
	ValueType string
	Entries   []*MapEntryExpr
}

type MapEntryExpr struct {
	Key   Expr
	Value Expr
}

type CallExpr struct {
	Callee    string
	Arguments []Expr
}

type LambdaExpr struct {
	Params     []*Param
	ReturnType string
	Body       *Block
	Captures   []string
}

type Pattern interface {
	patternNode()
}

type WildcardPattern struct{}

type VarPattern struct {
	Name string
}

type LiteralPattern struct {
	Literal Expr
}

type RecordPattern struct {
	TypeName string
	Fields   []*RecordFieldPattern
}

type VariantPattern struct {
	TypeName string
	Variant  string
	Fields   []Pattern
}

type RecordFieldPattern struct {
	Field   string
	Pattern Pattern
}

func (VarDecl) stmtNode()    {}
func (PrintStmt) stmtNode()  {}
func (ExprStmt) stmtNode()   {}
func (AssignStmt) stmtNode() {}
func (ReturnStmt) stmtNode() {}

func (IntLiteral) exprNode()      {}
func (BoolLiteral) exprNode()     {}
func (NullLiteral) exprNode()     {}
func (StringLiteral) exprNode()   {}
func (BinaryOp) exprNode()        {}
func (VarRef) exprNode()          {}
func (IfExpr) exprNode()          {}
func (TernaryExpr) exprNode()     {}
func (ElvisExpr) exprNode()       {}
func (MatchExpr) exprNode()       {}
func (RecordLiteral) exprNode()   {}
func (FieldAccess) exprNode()     {}
func (SafeFieldAccess) exprNode() {}
func (IndexAccess) exprNode()     {}
func (ArrayAllocExpr) exprNode()  {}
func (MapAllocExpr) exprNode()    {}
func (MapLiteralExpr) exprNode()  {}
func (CallExpr) exprNode()        {}
func (LambdaExpr) exprNode()      {}

func (WildcardPattern) patternNode()    {}
func (VarPattern) patternNode()         {}
func (LiteralPattern) patternNode()     {}
func (RecordPattern) patternNode()      {}
func (VariantPattern) patternNode()     {}
func (RecordFieldPattern) patternNode() {}
