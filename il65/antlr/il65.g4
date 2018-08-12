/*
IL65 combined lexer and parser grammar

NOTES:

- whitespace is ignored. (tabs/spaces)
- every position can be empty, be a comment, or contain ONE statement.

*/

grammar il65;


LINECOMMENT : [\r\n][ \t]* COMMENT -> channel(HIDDEN);
COMMENT :  ';' ~[\r\n]* -> channel(HIDDEN) ;
WS :  [ \t] -> skip ;
EOL :  [\r\n]+ ;
NAME :  [a-zA-Z_][a-zA-Z0-9_]* ;
DEC_INTEGER :  ('0'..'9') | (('1'..'9')('0'..'9')+);
HEX_INTEGER :  '$' (('a'..'f') | ('A'..'F') | ('0'..'9'))+ ;
BIN_INTEGER :  '%' ('0' | '1')+ ;

FLOAT_NUMBER :  FNUMBER (('E'|'e') ('+' | '-')? FNUMBER)? ;	// sign comes later from unary expression
fragment FNUMBER :  ('0' .. '9') + ('.' ('0' .. '9') +)? ;

fragment STRING_ESCAPE_SEQ :  '\\' . | '\\' EOL;
STRING :
	'"' ( STRING_ESCAPE_SEQ | ~[\\\r\n\f"] )* '"'
	{
		// get rid of the enclosing quotes
		String s = getText();
		setText(s.substring(1, s.length() - 1));
	}
	;
INLINEASMBLOCK :
	'{{' .+? '}}'
	{
		// get rid of the enclosing double braces
		String s = getText();
		setText(s.substring(2, s.length() - 2));
	}
	;


module :  (modulestatement | EOL)* EOF ;

modulestatement:  directive | block ;

block:
	'~' identifier integerliteral? '{' EOL
 		(statement | EOL)*
 	'}' EOL
 	;

statement :
	directive
	| varinitializer
	| vardecl
	| constdecl
	| memoryvardecl
	| assignment
	| augassignment
	| unconditionaljump
	| postincrdecr
	| inlineasm
	| label
	// @todo forloop, whileloop, repeatloop, ifelse
	;

label :  identifier ':'  ;

call_location :  integerliteral | identifier | scoped_identifier ;

unconditionaljump :  'goto'  call_location ;

directive :
	directivename=('%output' | '%launcher' | '%zp' | '%address' | '%import' |
                       '%breakpoint' | '%asminclude' | '%asmbinary')
        (directivearg? | directivearg (',' directivearg)*)
        ;

directivearg : stringliteral | identifier | integerliteral ;

vardecl:  datatype arrayspec? identifier ;

varinitializer : datatype arrayspec? identifier '=' expression ;

constdecl: 'const' varinitializer ;

memoryvardecl: 'memory' varinitializer;

datatype:  'byte' | 'word' | 'float' | 'str' | 'str_p' | 'str_s' | 'str_ps' ;

arrayspec:  '[' expression (',' expression)? ']' ;

assignment :  assign_target '=' expression ;

augassignment :
	assign_target operator=('+=' | '-=' | '/=' | '*=' | '**=' |
	               '<<=' | '>>=' | '<<@=' | '>>@=' | '&=' | '|=' | '^=') expression
	;

assign_target:
	register
	| identifier
	| scoped_identifier
	;

postincrdecr :  assign_target  operator = ('++' | '--') ;

expression :
	'(' expression ')'
	| expression arrayspec
	| functioncall
	| prefix = ('+'|'-'|'~') expression
	| left = expression bop = '**' right = expression
	| left = expression bop = ('*' | '/' ) right = expression
	| left = expression bop = ('+' | '-' ) right = expression
	| left = expression bop = ('<<' | '>>' | '<<@' | '>>@' ) right = expression
	| left = expression bop = ('<' | '>' | '<=' | '>=') right = expression
	| left = expression bop = ('==' | '!=') right = expression
	| left = expression bop = '&' right = expression
	| left = expression bop = '^' right = expression
	| left = expression bop = '|' right = expression
	| left = expression bop = 'and' right = expression
	| left = expression bop = 'or' right = expression
	| left = expression bop = 'xor' right = expression
	| prefix = 'not' expression
	| rangefrom = expression 'to' rangeto = expression
	| literalvalue
	| register
	| identifier
	| scoped_identifier
	;


functioncall :
	call_location '(' function_arg_list? ')'
	;

function_arg_list :
	expression (',' expression)*
	;

identifier :  NAME ;

scoped_identifier :  NAME ('.' NAME)+ ;

register :  'A' | 'X' | 'Y' | 'AX' | 'AY' | 'XY' | 'SC' | 'SI' | 'SZ' ;

integerliteral :  DEC_INTEGER | HEX_INTEGER | BIN_INTEGER ;

booleanliteral :  'true' | 'false' ;

arrayliteral :  '[' expression (',' expression)* ']' ;

stringliteral :  STRING ;

floatliteral :  FLOAT_NUMBER ;

literalvalue :
	integerliteral
	| booleanliteral
	| arrayliteral
	| stringliteral
	| floatliteral
	;

inlineasm :  '%asm' INLINEASMBLOCK;