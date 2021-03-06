; Prog8 internal library routines - always included by the compiler
;
; Written by Irmen de Jong (irmen@razorvine.net) - license: GNU GPL 3.0
;
; indent format: TABS, size=8


init_system	.proc
	; -- initializes the machine to a sane starting state
	; Called automatically by the loader program logic.
	; This means that the BASIC, KERNAL and CHARGEN ROMs are banked in,
	; the VIC, SID and CIA chips are reset, screen is cleared, and the default IRQ is set.
	; Also a different color scheme is chosen to identify ourselves a little.
	; Uppercase charset is activated, and all three registers set to 0, status flags cleared.
		sei
		cld
		lda  #%00101111
		sta  $00
		lda  #%00100111
		sta  $01
		jsr  c64.IOINIT
		jsr  c64.RESTOR
		jsr  c64.CINT
		lda  #6
		sta  c64.EXTCOL
		lda  #7
		sta  c64.COLOR
		lda  #0
		sta  c64.BGCOL0
		tax
		tay
		clc
		clv
		cli
		rts
		.pend


read_byte_from_address	.proc
	; -- read the byte from the memory address on the top of the stack, return in A (stack remains unchanged)
		lda  c64.ESTACK_LO+1,x
		ldy  c64.ESTACK_HI+1,x
		sta  (+) +1
		sty  (+) +2
+		lda  $ffff		; modified
		rts
		.pend


add_a_to_zpword	.proc
	; -- add ubyte in A to the uword in c64.SCRATCH_ZPWORD1
		clc
		adc  c64.SCRATCH_ZPWORD1
		sta  c64.SCRATCH_ZPWORD1
		bcc  +
		inc  c64.SCRATCH_ZPWORD1+1
+		rts
		.pend

pop_index_times_5	.proc
		inx
		lda  c64.ESTACK_LO,x
		asl  a
		asl  a
		clc
		adc  c64.ESTACK_LO,x
		rts
		.pend

neg_b		.proc
		lda  #0
		sec
		sbc  c64.ESTACK_LO+1,x
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

neg_w		.proc
		sec
		lda  #0
		sbc  c64.ESTACK_LO+1,x
		sta  c64.ESTACK_LO+1,x
		lda  #0
		sbc  c64.ESTACK_HI+1,x
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

inv_word	.proc
		lda  c64.ESTACK_LO+1,x
		eor  #255
		sta  c64.ESTACK_LO+1,x
		lda  c64.ESTACK_HI+1,x
		eor  #255
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

not_byte	.proc
		lda  c64.ESTACK_LO+1,x
		beq  +
		lda  #1
+		eor  #1
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

not_word	.proc
		lda  c64.ESTACK_LO + 1,x
		ora  c64.ESTACK_HI + 1,x
		beq  +
		lda  #1
+		eor  #1
		sta  c64.ESTACK_LO + 1,x
		lsr  a
		sta  c64.ESTACK_HI + 1,x
		rts
		.pend

bitand_b	.proc
		; -- bitwise and (of 2 bytes)
		lda  c64.ESTACK_LO+2,x
		and  c64.ESTACK_LO+1,x
		inx
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

bitor_b		.proc
		; -- bitwise or (of 2 bytes)
		lda  c64.ESTACK_LO+2,x
		ora  c64.ESTACK_LO+1,x
		inx
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

bitxor_b	.proc
		; -- bitwise xor (of 2 bytes)
		lda  c64.ESTACK_LO+2,x
		eor  c64.ESTACK_LO+1,x
		inx
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

bitand_w	.proc
		; -- bitwise and (of 2 words)
		lda  c64.ESTACK_LO+2,x
		and  c64.ESTACK_LO+1,x
		sta  c64.ESTACK_LO+2,x
		lda  c64.ESTACK_HI+2,x
		and  c64.ESTACK_HI+1,x
		sta  c64.ESTACK_HI+2,x
		inx
		rts
		.pend

bitor_w		.proc
		; -- bitwise or (of 2 words)
		lda  c64.ESTACK_LO+2,x
		ora  c64.ESTACK_LO+1,x
		sta  c64.ESTACK_LO+2,x
		lda  c64.ESTACK_HI+2,x
		ora  c64.ESTACK_HI+1,x
		sta  c64.ESTACK_HI+2,x
		inx
		rts
		.pend

bitxor_w	.proc
		; -- bitwise xor (of 2 bytes)
		lda  c64.ESTACK_LO+2,x
		eor  c64.ESTACK_LO+1,x
		sta  c64.ESTACK_LO+2,x
		lda  c64.ESTACK_HI+2,x
		eor  c64.ESTACK_HI+1,x
		sta  c64.ESTACK_HI+2,x
		inx
		rts
		.pend

and_b		.proc
		; -- logical and (of 2 bytes)
		lda  c64.ESTACK_LO+2,x
		beq  +
		lda  #1
+		sta  c64.SCRATCH_ZPB1
		lda  c64.ESTACK_LO+1,x
		beq  +
		lda  #1
+		and  c64.SCRATCH_ZPB1
		inx
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

or_b		.proc
		; -- logical or (of 2 bytes)
		lda  c64.ESTACK_LO+2,x
		ora  c64.ESTACK_LO+1,x
		beq  +
		lda  #1
+		inx
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

xor_b		.proc
		; -- logical xor (of 2 bytes)
		lda  c64.ESTACK_LO+2,x
		beq  +
		lda  #1
+		sta  c64.SCRATCH_ZPB1
		lda  c64.ESTACK_LO+1,x
		beq  +
		lda  #1
+		eor  c64.SCRATCH_ZPB1
		inx
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

and_w		.proc
		; -- logical and (word and word -> byte)
		lda  c64.ESTACK_LO+2,x
		ora  c64.ESTACK_HI+2,x
		beq  +
		lda  #1
+		sta  c64.SCRATCH_ZPB1
		lda  c64.ESTACK_LO+1,x
		ora  c64.ESTACK_HI+1,x
		beq  +
		lda  #1
+		and  c64.SCRATCH_ZPB1
		inx
 		sta  c64.ESTACK_LO+1,x
 		sta  c64.ESTACK_HI+1,x
		rts
		.pend

or_w		.proc
		; -- logical or (word or word -> byte)
		lda  c64.ESTACK_LO+2,x
		ora  c64.ESTACK_LO+1,x
		ora  c64.ESTACK_HI+2,x
		ora  c64.ESTACK_HI+1,x
		beq  +
		lda  #1
+		inx
		sta  c64.ESTACK_LO+1,x
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

xor_w		.proc
		; -- logical xor (word xor word -> byte)
		lda  c64.ESTACK_LO+2,x
		ora  c64.ESTACK_HI+2,x
		beq  +
		lda  #1
+		sta  c64.SCRATCH_ZPB1
		lda  c64.ESTACK_LO+1,x
		ora  c64.ESTACK_HI+1,x
		beq  +
		lda  #1
+		eor  c64.SCRATCH_ZPB1
		inx
 		sta  c64.ESTACK_LO+1,x
 		sta  c64.ESTACK_HI+1,x
		rts
		.pend


abs_b		.proc
	; -- push abs(byte) on stack (as byte)
		lda  c64.ESTACK_LO+1,x
		bmi  neg_b
		rts
		.pend

abs_w		.proc
	; -- push abs(word) on stack (as word)
		lda  c64.ESTACK_HI+1,x
		bmi  neg_w
		rts
		.pend

add_w		.proc
	; -- push word+word / uword+uword
		inx
		clc
		lda  c64.ESTACK_LO,x
		adc  c64.ESTACK_LO+1,x
		sta  c64.ESTACK_LO+1,x
		lda  c64.ESTACK_HI,x
		adc  c64.ESTACK_HI+1,x
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

sub_w		.proc
	; -- push word-word
		inx
		sec
		lda  c64.ESTACK_LO+1,x
		sbc  c64.ESTACK_LO,x
		sta  c64.ESTACK_LO+1,x
		lda  c64.ESTACK_HI+1,x
		sbc  c64.ESTACK_HI,x
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

mul_byte	.proc
	; -- b*b->b (signed and unsigned)
		inx
		lda  c64.ESTACK_LO,x
		ldy  c64.ESTACK_LO+1,x
		jsr  math.multiply_bytes
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

mul_word	.proc
		inx
		lda  c64.ESTACK_LO,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI,x
		sta  c64.SCRATCH_ZPWORD1+1
		lda  c64.ESTACK_LO+1,x
		ldy  c64.ESTACK_HI+1,x
		stx  c64.SCRATCH_ZPREGX
		jsr  math.multiply_words
		ldx  c64.SCRATCH_ZPREGX
		lda  math.multiply_words.result
		sta  c64.ESTACK_LO+1,x
		lda  math.multiply_words.result+1
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

idiv_b		.proc
	; signed division: use unsigned division and fix sign of result afterwards
		inx
		lda  c64.ESTACK_LO,x
		eor  c64.ESTACK_LO+1,x
		php			; save sign of result
		lda  c64.ESTACK_LO,x
		bpl  +
		eor  #$ff
		sec
		adc  #0			; make num1 positive
+		tay
		inx
		lda  c64.ESTACK_LO,x
		bpl  +
		eor  #$ff
		sec
		adc  #0			; make num2 positive
+		jsr  math.divmod_ub
		sta  _remainder
		tya
		plp			; get sign of result
		bpl  +
		eor  #$ff
		sec
		adc  #0			; negate result
+		sta  c64.ESTACK_LO,x
		dex
		rts
_remainder	.byte  0
		.pend

idiv_ub		.proc
		inx
		ldy  c64.ESTACK_LO,x
		lda  c64.ESTACK_LO+1,x
		jsr  math.divmod_ub
		tya
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

idiv_w		.proc
	; signed division: use unsigned division and fix sign of result afterwards
		lda  c64.ESTACK_HI+2,x
		eor  c64.ESTACK_HI+1,x
		php				; save sign of result
		lda  c64.ESTACK_HI+1,x
		bpl  +
		jsr  neg_w			; make value positive
+		inx
		lda  c64.ESTACK_HI+1,x
		bpl  +
		jsr  neg_w			; make value positive
+		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD1+1
		lda  c64.ESTACK_LO,x
		ldy  c64.ESTACK_HI,x
		jsr  math.divmod_uw_asm
		sta  c64.ESTACK_LO+1,x
		tya
		sta  c64.ESTACK_HI+1,x
		plp
		bpl  +
		jmp  neg_w		; negate result
+		rts
		.pend

idiv_uw		.proc
		inx
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD1+1
		lda  c64.ESTACK_LO,x
		ldy  c64.ESTACK_HI,x
		jsr  math.divmod_uw_asm
		sta  c64.ESTACK_LO+1,x
		tya
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

remainder_ub	.proc
		inx
		ldy  c64.ESTACK_LO,x	; right operand
		lda  c64.ESTACK_LO+1,x  ; left operand
		jsr  math.divmod_ub
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

remainder_uw	.proc
		inx
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD1+1
		lda  c64.ESTACK_LO,x
		ldy  c64.ESTACK_HI,x
		jsr  math.divmod_uw_asm
		lda  c64.SCRATCH_ZPWORD2
		sta  c64.ESTACK_LO+1,x
		lda  c64.SCRATCH_ZPWORD2+1
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

equal_w		.proc
	; -- are the two words on the stack identical?
		lda  c64.ESTACK_LO+1,x
		cmp  c64.ESTACK_LO+2,x
		bne  equal_b._equal_b_false
		lda  c64.ESTACK_HI+1,x
		cmp  c64.ESTACK_HI+2,x
		bne  equal_b._equal_b_false
		beq  equal_b._equal_b_true
		.pend

notequal_b	.proc
	; -- are the two bytes on the stack different?
		lda  c64.ESTACK_LO+1,x
		cmp  c64.ESTACK_LO+2,x
		beq  equal_b._equal_b_false
		bne  equal_b._equal_b_true
		.pend

notequal_w	.proc
	; -- are the two words on the stack different?
		lda  c64.ESTACK_HI+1,x
		cmp  c64.ESTACK_HI+2,x
		beq  notequal_b
		bne  equal_b._equal_b_true
		.pend

less_ub		.proc
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		bcc  equal_b._equal_b_true
		bcs  equal_b._equal_b_false
		.pend

less_b		.proc
	; see http://www.6502.org/tutorials/compare_beyond.html
		lda  c64.ESTACK_LO+2,x
		sec
		sbc  c64.ESTACK_LO+1,x
		bvc  +
		eor  #$80
+		bmi  equal_b._equal_b_true
		bpl  equal_b._equal_b_false
		.pend

less_uw		.proc
		lda  c64.ESTACK_HI+2,x
		cmp  c64.ESTACK_HI+1,x
		bcc  equal_b._equal_b_true
		bne  equal_b._equal_b_false
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		bcc  equal_b._equal_b_true
		bcs  equal_b._equal_b_false
		.pend

less_w		.proc
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		lda  c64.ESTACK_HI+2,x
		sbc  c64.ESTACK_HI+1,x
		bvc  +
		eor  #$80
+		bmi  equal_b._equal_b_true
		bpl  equal_b._equal_b_false
		.pend

equal_b		.proc
	; -- are the two bytes on the stack identical?
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		bne  _equal_b_false
_equal_b_true	lda  #1
_equal_b_store	inx
		sta  c64.ESTACK_LO+1,x
		rts
_equal_b_false	lda  #0
		beq  _equal_b_store
		.pend

lesseq_ub	.proc
		lda  c64.ESTACK_LO+1,x
		cmp  c64.ESTACK_LO+2,x
		bcs  equal_b._equal_b_true
		bcc  equal_b._equal_b_false
		.pend

lesseq_b	.proc
	; see http://www.6502.org/tutorials/compare_beyond.html
		lda  c64.ESTACK_LO+2,x
		clc
		sbc  c64.ESTACK_LO+1,x
		bvc  +
		eor  #$80
+		bmi  equal_b._equal_b_true
		bpl  equal_b._equal_b_false
		.pend

lesseq_uw	.proc
		lda  c64.ESTACK_HI+1,x
		cmp  c64.ESTACK_HI+2,x
		bcc  equal_b._equal_b_false
		bne  equal_b._equal_b_true
		lda  c64.ESTACK_LO+1,x
		cmp  c64.ESTACK_LO+2,x
		bcs  equal_b._equal_b_true
		bcc  equal_b._equal_b_false
		.pend

lesseq_w	.proc
		lda  c64.ESTACK_LO+1,x
		cmp  c64.ESTACK_LO+2,x
		lda  c64.ESTACK_HI+1,x
		sbc  c64.ESTACK_HI+2,x
		bvc  +
		eor  #$80
+		bpl  equal_b._equal_b_true
		bmi  equal_b._equal_b_false
		.pend

greater_ub	.proc
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		beq  equal_b._equal_b_false
		bcs  equal_b._equal_b_true
		bcc  equal_b._equal_b_false
		.pend

greater_b	.proc
	; see http://www.6502.org/tutorials/compare_beyond.html
		lda  c64.ESTACK_LO+2,x
		clc
		sbc  c64.ESTACK_LO+1,x
		bvc  +
		eor  #$80
+		bpl  equal_b._equal_b_true
		bmi  equal_b._equal_b_false
		.pend

greater_uw	.proc
		lda  c64.ESTACK_HI+1,x
		cmp  c64.ESTACK_HI+2,x
		bcc  equal_b._equal_b_true
		bne  equal_b._equal_b_false
		lda  c64.ESTACK_LO+1,x
		cmp  c64.ESTACK_LO+2,x
		bcc  equal_b._equal_b_true
		bcs  equal_b._equal_b_false
		.pend

greater_w	.proc
		lda  c64.ESTACK_LO+1,x
		cmp  c64.ESTACK_LO+2,x
		lda  c64.ESTACK_HI+1,x
		sbc  c64.ESTACK_HI+2,x
		bvc  +
		eor  #$80
+		bmi  equal_b._equal_b_true
		bpl  equal_b._equal_b_false
		.pend

greatereq_ub	.proc
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		bcs  equal_b._equal_b_true
		bcc  equal_b._equal_b_false
		.pend

greatereq_b	.proc
	; see http://www.6502.org/tutorials/compare_beyond.html
		lda  c64.ESTACK_LO+2,x
		sec
		sbc  c64.ESTACK_LO+1,x
		bvc  +
		eor  #$80
+		bpl  equal_b._equal_b_true
		bmi  equal_b._equal_b_false
		.pend

greatereq_uw	.proc
		lda  c64.ESTACK_HI+2,x
		cmp  c64.ESTACK_HI+1,x
		bcc  equal_b._equal_b_false
		bne  equal_b._equal_b_true
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		bcs  equal_b._equal_b_true
		bcc  equal_b._equal_b_false
		.pend

greatereq_w	.proc
		lda  c64.ESTACK_LO+2,x
		cmp  c64.ESTACK_LO+1,x
		lda  c64.ESTACK_HI+2,x
		sbc  c64.ESTACK_HI+1,x
		bvc  +
		eor  #$80
+		bpl  equal_b._equal_b_true
		bmi  equal_b._equal_b_false
		.pend

func_read_flags	.proc
		; -- put the processor status register on the stack
		php
		pla
		sta  c64.ESTACK_LO,x
		dex
		rts
		.pend


func_sqrt16	.proc
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD2
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD2+1
		stx  c64.SCRATCH_ZPREGX
		ldy  #$00    ; r = 0
		ldx  #$07
		clc         ; clear bit 16 of m
_loop
		tya
		ora  _stab-1,x
		sta  c64.SCRATCH_ZPB1     ; (r asl 8) | (d asl 7)
		lda  c64.SCRATCH_ZPWORD2+1
		bcs  _skip0  ; m >= 65536? then t <= m is always true
		cmp  c64.SCRATCH_ZPB1
		bcc  _skip1  ; t <= m
_skip0
		sbc  c64.SCRATCH_ZPB1
		sta  c64.SCRATCH_ZPWORD2+1     ; m = m - t
		tya
		ora  _stab,x
		tay         ; r = r or d
_skip1
		asl  c64.SCRATCH_ZPWORD2
		rol  c64.SCRATCH_ZPWORD2+1     ; m = m asl 1
		dex
		bne  _loop

		; last iteration
		bcs  _skip2
		sty  c64.SCRATCH_ZPB1
		lda  c64.SCRATCH_ZPWORD2
		cmp  #$80
		lda  c64.SCRATCH_ZPWORD2+1
		sbc  c64.SCRATCH_ZPB1
		bcc  _skip3
_skip2
		iny         ; r = r or d (d is 1 here)
_skip3
		ldx  c64.SCRATCH_ZPREGX
		tya
		sta  c64.ESTACK_LO+1,x
		lda  #0
		sta  c64.ESTACK_HI+1,x
		rts
_stab   .byte $01,$02,$04,$08,$10,$20,$40,$80
		.pend


func_sin8	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  _sinecos8,y
		sta  c64.ESTACK_LO+1,x
		rts
_sinecos8	.char  trunc(127.0 * sin(range(256+64) * rad(360.0/256.0)))
		.pend

func_sin8u	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  _sinecos8u,y
		sta  c64.ESTACK_LO+1,x
		rts
_sinecos8u	.byte  trunc(128.0 + 127.5 * sin(range(256+64) * rad(360.0/256.0)))
		.pend

func_sin16	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  _sinecos8lo,y
		sta  c64.ESTACK_LO+1,x
		lda  _sinecos8hi,y
		sta  c64.ESTACK_HI+1,x
		rts

_  :=  trunc(32767.0 * sin(range(256+64) * rad(360.0/256.0)))
_sinecos8lo     .byte  <_
_sinecos8hi     .byte  >_
		.pend

func_sin16u	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  _sinecos8ulo,y
		sta  c64.ESTACK_LO+1,x
		lda  _sinecos8uhi,y
		sta  c64.ESTACK_HI+1,x
		rts

_  :=  trunc(32768.0 + 32767.5 * sin(range(256+64) * rad(360.0/256.0)))
_sinecos8ulo     .byte  <_
_sinecos8uhi     .byte  >_
		.pend

func_cos8	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  func_sin8._sinecos8+64,y
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

func_cos8u	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  func_sin8u._sinecos8u+64,y
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

func_cos16	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  func_sin16._sinecos8lo+64,y
		sta  c64.ESTACK_LO+1,x
		lda  func_sin16._sinecos8hi+64,y
		sta  c64.ESTACK_HI+1,x
		rts
		.pend

func_cos16u	.proc
		ldy  c64.ESTACK_LO+1,x
		lda  func_sin16u._sinecos8ulo+64,y
		sta  c64.ESTACK_LO+1,x
		lda  func_sin16u._sinecos8uhi+64,y
		sta  c64.ESTACK_HI+1,x
		rts
		.pend


peek_address	.proc
	; -- peek address on stack into c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD1+1
		rts
		.pend

func_any_b	.proc
		inx
		lda  c64.ESTACK_LO,x	; array size
_entry		sta  _cmp_mod+1		; self-modifying code
		jsr  peek_address
		ldy  #0
-		lda  (c64.SCRATCH_ZPWORD1),y
		bne  _got_any
		iny
_cmp_mod	cpy  #255		; modified
		bne  -
		lda  #0
		sta  c64.ESTACK_LO+1,x
		rts
_got_any	lda  #1
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

func_any_w	.proc
		inx
		lda  c64.ESTACK_LO,x	; array size
		asl  a			; times 2 because of word
		jmp  func_any_b._entry
		.pend

func_all_b	.proc
		inx
		lda  c64.ESTACK_LO,x	; array size
		sta  _cmp_mod+1		; self-modifying code
		jsr  peek_address
		ldy  #0
-		lda  (c64.SCRATCH_ZPWORD1),y
		beq  _got_not_all
		iny
_cmp_mod	cpy  #255		; modified
		bne  -
		lda  #1
		sta  c64.ESTACK_LO+1,x
		rts
_got_not_all	lda  #0
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

func_all_w	.proc
		inx
		lda  c64.ESTACK_LO,x	; array size
		asl  a			; times 2 because of word
		sta  _cmp_mod+1		; self-modifying code
		jsr  peek_address
		ldy  #0
-		lda  (c64.SCRATCH_ZPWORD1),y
		bne  +
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		bne  ++
		lda  #0
		sta  c64.ESTACK_LO+1,x
		rts
+		iny
+		iny
_cmp_mod	cpy  #255		; modified
		bne  -
		lda  #1
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

func_max_ub	.proc
		jsr  pop_array_and_lengthmin1Y
		lda  #0
		sta  c64.SCRATCH_ZPB1
-		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  c64.SCRATCH_ZPB1
		bcc  +
		sta  c64.SCRATCH_ZPB1
+		dey
		cpy  #255
		bne  -
		lda  c64.SCRATCH_ZPB1
		sta  c64.ESTACK_LO,x
		dex
		rts
		.pend

func_max_b	.proc
		jsr  pop_array_and_lengthmin1Y
		lda  #-128
		sta  c64.SCRATCH_ZPB1
-		lda  (c64.SCRATCH_ZPWORD1),y
		sec
		sbc  c64.SCRATCH_ZPB1
		bvc  +
		eor  #$80
+		bmi  +
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  c64.SCRATCH_ZPB1
+		dey
		cpy  #255
		bne  -
		lda  c64.SCRATCH_ZPB1
		sta  c64.ESTACK_LO,x
		dex
		rts
		.pend

func_max_uw	.proc
		lda  #0
		sta  _result_maxuw
		sta  _result_maxuw+1
		jsr  pop_array_and_lengthmin1Y
		tya
		asl  a
		tay
_loop
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		dey
		cmp  _result_maxuw+1
		bcc  _lesseq
		bne  _greater
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  _result_maxuw
		bcc  _lesseq
_greater	lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_maxuw
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_maxuw+1
		dey
_lesseq		dey
		dey
		cpy  #254
		bne  _loop
		lda  _result_maxuw
		sta  c64.ESTACK_LO,x
		lda  _result_maxuw+1
		sta  c64.ESTACK_HI,x
		dex
		rts
_result_maxuw	.word  0
		.pend

func_max_w	.proc
		lda  #$00
		sta  _result_maxw
		lda  #$80
		sta  _result_maxw+1
		jsr  pop_array_and_lengthmin1Y
		tya
		asl  a
		tay
_loop
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  _result_maxw
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		dey
		sbc  _result_maxw+1
		bvc  +
		eor  #$80
+		bmi  _lesseq
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_maxw
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_maxw+1
		dey
_lesseq		dey
		dey
		cpy  #254
		bne  _loop
		lda  _result_maxw
		sta  c64.ESTACK_LO,x
		lda  _result_maxw+1
		sta  c64.ESTACK_HI,x
		dex
		rts
_result_maxw	.word  0
		.pend


func_sum_b	.proc
		jsr  pop_array_and_lengthmin1Y
		lda  #0
		sta  c64.ESTACK_LO,x
		sta  c64.ESTACK_HI,x
_loop		lda  (c64.SCRATCH_ZPWORD1),y
		pha
		clc
		adc  c64.ESTACK_LO,x
		sta  c64.ESTACK_LO,x
		; sign extend the high byte
		pla
		and  #$80
		beq  +
		lda  #$ff
+		adc  c64.ESTACK_HI,x
		sta  c64.ESTACK_HI,x
		dey
		cpy  #255
		bne  _loop
		dex
		rts
		.pend

func_sum_ub	.proc
		jsr  pop_array_and_lengthmin1Y
		lda  #0
		sta  c64.ESTACK_HI,x
-		clc
		adc  (c64.SCRATCH_ZPWORD1),y
		bcc  +
		inc  c64.ESTACK_HI,x
+		dey
		cpy  #255
		bne  -
		sta  c64.ESTACK_LO,x
		dex
		rts
		.pend

func_sum_uw	.proc
		jsr  pop_array_and_lengthmin1Y
		tya
		asl  a
		tay
		lda  #0
		sta  c64.ESTACK_LO,x
		sta  c64.ESTACK_HI,x
-		lda  (c64.SCRATCH_ZPWORD1),y
		iny
		clc
		adc  c64.ESTACK_LO,x
		sta  c64.ESTACK_LO,x
		lda  (c64.SCRATCH_ZPWORD1),y
		adc  c64.ESTACK_HI,x
		sta  c64.ESTACK_HI,x
		dey
		dey
		dey
		cpy  #254
		bne  -
		dex
		rts
		.pend

func_sum_w	.proc
		jmp  func_sum_uw
		.pend


pop_array_and_lengthmin1Y	.proc
		inx
		ldy  c64.ESTACK_LO,x
		dey				; length minus 1, for iteration
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD1+1
		inx
		rts
		.pend

func_min_ub	.proc
		jsr  pop_array_and_lengthmin1Y
		lda  #255
		sta  c64.SCRATCH_ZPB1
-		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  c64.SCRATCH_ZPB1
		bcs  +
		sta  c64.SCRATCH_ZPB1
+		dey
		cpy  #255
		bne  -
		lda  c64.SCRATCH_ZPB1
		sta  c64.ESTACK_LO,x
		dex
		rts
		.pend


func_min_b	.proc
		jsr  pop_array_and_lengthmin1Y
		lda  #127
		sta  c64.SCRATCH_ZPB1
-		lda  (c64.SCRATCH_ZPWORD1),y
		clc
		sbc  c64.SCRATCH_ZPB1
		bvc  +
		eor  #$80
+		bpl  +
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  c64.SCRATCH_ZPB1
+		dey
		cpy  #255
		bne  -
		lda  c64.SCRATCH_ZPB1
		sta  c64.ESTACK_LO,x
		dex
		rts
		.pend

func_min_uw	.proc
		lda  #$ff
		sta  _result_minuw
		sta  _result_minuw+1
		jsr  pop_array_and_lengthmin1Y
		tya
		asl  a
		tay
_loop
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		dey
		cmp   _result_minuw+1
		bcc  _less
		bne  _gtequ
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  _result_minuw
		bcs  _gtequ
_less		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_minuw
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_minuw+1
		dey
_gtequ		dey
		dey
		cpy  #254
		bne  _loop
		lda  _result_minuw
		sta  c64.ESTACK_LO,x
		lda  _result_minuw+1
		sta  c64.ESTACK_HI,x
		dex
		rts
_result_minuw	.word  0
		.pend

func_min_w	.proc
		lda  #$ff
		sta  _result_minw
		lda  #$7f
		sta  _result_minw+1
		jsr  pop_array_and_lengthmin1Y
		tya
		asl  a
		tay
_loop
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp   _result_minw
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		dey
		sbc  _result_minw+1
		bvc  +
		eor  #$80
+		bpl  _gtequ
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_minw
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _result_minw+1
		dey
_gtequ		dey
		dey
		cpy  #254
		bne  _loop
		lda  _result_minw
		sta  c64.ESTACK_LO,x
		lda  _result_minw+1
		sta  c64.ESTACK_HI,x
		dex
		rts
_result_minw	.word  0
		.pend

func_strlen	.proc
	; -- push length of 0-terminated string on stack
		jsr  peek_address
		ldy  #0
-		lda  (c64.SCRATCH_ZPWORD1),y
		beq  +
		iny
		bne  -
+		tya
		sta  c64.ESTACK_LO+1,x
		rts
		.pend

func_rnd	.proc
	; -- put a random ubyte on the estack
		jsr  math.randbyte
		sta  c64.ESTACK_LO,x
		dex
		rts
		.pend

func_rndw	.proc
	; -- put a random uword on the estack
		jsr  math.randword
		sta  c64.ESTACK_LO,x
		tya
		sta  c64.ESTACK_HI,x
		dex
		rts
		.pend


func_memcopy	.proc
	; note: clobbers A,Y
		inx
		stx  c64.SCRATCH_ZPREGX
		lda  c64.ESTACK_LO+2,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+2,x
		sta  c64.SCRATCH_ZPWORD1+1
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD2
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD2+1
		lda  c64.ESTACK_LO,x
		tax
		ldy  #0
-		lda  (c64.SCRATCH_ZPWORD1), y
		sta  (c64.SCRATCH_ZPWORD2), y
		iny
		dex
		bne  -
		ldx  c64.SCRATCH_ZPREGX
		inx
		inx
		rts
		.pend

func_memset	.proc
	; note: clobbers A,Y
		inx
		stx  c64.SCRATCH_ZPREGX
		lda  c64.ESTACK_LO+2,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+2,x
		sta  c64.SCRATCH_ZPWORD1+1
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPB1
		ldy  c64.ESTACK_HI+1,x
		lda  c64.ESTACK_LO,x
		ldx  c64.SCRATCH_ZPB1
		jsr  memset
		ldx  c64.SCRATCH_ZPREGX
		inx
		inx
		rts
		.pend

func_memsetw	.proc
	; note: clobbers A,Y
		; -- fill memory from (SCRATCH_ZPWORD1) number of words in SCRATCH_ZPWORD2, with word value in AY.

		inx
		stx  c64.SCRATCH_ZPREGX
		lda  c64.ESTACK_LO+2,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI+2,x
		sta  c64.SCRATCH_ZPWORD1+1
		lda  c64.ESTACK_LO+1,x
		sta  c64.SCRATCH_ZPWORD2
		lda  c64.ESTACK_HI+1,x
		sta  c64.SCRATCH_ZPWORD2+1
		lda  c64.ESTACK_LO,x
		ldy  c64.ESTACK_HI,x
		jsr  memsetw
		ldx  c64.SCRATCH_ZPREGX
		inx
		inx
		rts
		.pend


memcopy16_up	.proc
	; -- copy memory UP from (SCRATCH_ZPWORD1) to (SCRATCH_ZPWORD2) of length X/Y (16-bit, X=lo, Y=hi)
	;    clobbers register A,X,Y
		source = c64.SCRATCH_ZPWORD1
		dest = c64.SCRATCH_ZPWORD2
		length = c64.SCRATCH_ZPB1   ; (and SCRATCH_ZPREG)

		stx  length
		sty  length+1

		ldx  length             ; move low byte of length into X
		bne  +                  ; jump to start if X > 0
		dec  length             ; subtract 1 from length
+		ldy  #0                 ; set Y to 0
-		lda  (source),y         ; set A to whatever (source) points to offset by Y
		sta  (dest),y           ; move A to location pointed to by (dest) offset by Y
		iny                     ; increment Y
		bne  +                  ; if Y<>0 then (rolled over) then still moving bytes
		inc  source+1           ; increment hi byte of source
		inc  dest+1             ; increment hi byte of dest
+		dex                     ; decrement X (lo byte counter)
		bne  -                  ; if X<>0 then move another byte
		dec  length             ; we've moved 255 bytes, dec length
		bpl  -                  ; if length is still positive go back and move more
		rts                     ; done
		.pend


memset          .proc
	; -- fill memory from (SCRATCH_ZPWORD1), length XY, with value in A.
	;    clobbers X, Y
		stx  c64.SCRATCH_ZPB1
		sty  c64.SCRATCH_ZPREG
		ldy  #0
		ldx  c64.SCRATCH_ZPREG
		beq  _lastpage

_fullpage	sta  (c64.SCRATCH_ZPWORD1),y
		iny
		bne  _fullpage
		inc  c64.SCRATCH_ZPWORD1+1          ; next page
		dex
		bne  _fullpage

_lastpage	ldy  c64.SCRATCH_ZPB1
		beq  +
-         	dey
		sta  (c64.SCRATCH_ZPWORD1),y
		bne  -

+           	rts
		.pend


memsetw		.proc
	; -- fill memory from (SCRATCH_ZPWORD1) number of words in SCRATCH_ZPWORD2, with word value in AY.
	;    clobbers A, X, Y
		sta  _mod1+1                    ; self-modify
		sty  _mod1b+1                   ; self-modify
		sta  _mod2+1                    ; self-modify
		sty  _mod2b+1                   ; self-modify
		ldx  c64.SCRATCH_ZPWORD1
		stx  c64.SCRATCH_ZPB1
		ldx  c64.SCRATCH_ZPWORD1+1
		inx
		stx  c64.SCRATCH_ZPREG                ; second page

		ldy  #0
		ldx  c64.SCRATCH_ZPWORD2+1
		beq  _lastpage

_fullpage
_mod1           lda  #0                         ; self-modified
		sta  (c64.SCRATCH_ZPWORD1),y        ; first page
		sta  (c64.SCRATCH_ZPB1),y            ; second page
		iny
_mod1b		lda  #0                         ; self-modified
		sta  (c64.SCRATCH_ZPWORD1),y        ; first page
		sta  (c64.SCRATCH_ZPB1),y            ; second page
		iny
		bne  _fullpage
		inc  c64.SCRATCH_ZPWORD1+1          ; next page pair
		inc  c64.SCRATCH_ZPWORD1+1          ; next page pair
		inc  c64.SCRATCH_ZPB1+1              ; next page pair
		inc  c64.SCRATCH_ZPB1+1              ; next page pair
		dex
		bne  _fullpage

_lastpage	ldx  c64.SCRATCH_ZPWORD2
		beq  _done

		ldy  #0
-
_mod2           lda  #0                         ; self-modified
                sta  (c64.SCRATCH_ZPWORD1), y
		inc  c64.SCRATCH_ZPWORD1
		bne  _mod2b
		inc  c64.SCRATCH_ZPWORD1+1
_mod2b          lda  #0                         ; self-modified
		sta  (c64.SCRATCH_ZPWORD1), y
		inc  c64.SCRATCH_ZPWORD1
		bne  +
		inc  c64.SCRATCH_ZPWORD1+1
+               dex
		bne  -
_done		rts
		.pend


sort_ub		.proc
		; 8bit unsigned sort
		; sorting subroutine coded by mats rosengren (mats.rosengren@esa.int)
		; input:  address of array to sort in c64.SCRATCH_ZPWORD1, length in c64.SCRATCH_ZPB1
		; first, put pointer BEFORE array
		lda  c64.SCRATCH_ZPWORD1
		bne  +
		dec  c64.SCRATCH_ZPWORD1+1
+		dec  c64.SCRATCH_ZPWORD1
_sortloop	ldy  c64.SCRATCH_ZPB1		;start of subroutine sort
		lda  (c64.SCRATCH_ZPWORD1),y	;last value in (what is left of) sequence to be sorted
		sta  c64.SCRATCH_ZPREG		;save value. will be over-written by largest number
		jmp  _l2
_l1		dey
		beq  _l3
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  c64.SCRATCH_ZPWORD2+1
		bcc  _l1
_l2		sty  c64.SCRATCH_ZPWORD2	;index of potentially largest value
		sta  c64.SCRATCH_ZPWORD2+1	;potentially largest value
		jmp  _l1
_l3		ldy  c64.SCRATCH_ZPB1		;where the largest value shall be put
		lda  c64.SCRATCH_ZPWORD2+1	;the largest value
		sta  (c64.SCRATCH_ZPWORD1),y	;put largest value in place
		ldy  c64.SCRATCH_ZPWORD2	;index of free space
		lda  c64.SCRATCH_ZPREG		;the over-written value
		sta  (c64.SCRATCH_ZPWORD1),y	;put the over-written value in the free space
		dec  c64.SCRATCH_ZPB1		;end of the shorter sequence still left
		bne  _sortloop			;start working with the shorter sequence
		rts
		.pend


sort_b		.proc
		; 8bit signed sort
		; sorting subroutine coded by mats rosengren (mats.rosengren@esa.int)
		; input:  address of array to sort in c64.SCRATCH_ZPWORD1, length in c64.SCRATCH_ZPB1
		; first, put pointer BEFORE array
		lda  c64.SCRATCH_ZPWORD1
		bne  +
		dec  c64.SCRATCH_ZPWORD1+1
+		dec  c64.SCRATCH_ZPWORD1
_sortloop	ldy  c64.SCRATCH_ZPB1		;start of subroutine sort
		lda  (c64.SCRATCH_ZPWORD1),y	;last value in (what is left of) sequence to be sorted
		sta  c64.SCRATCH_ZPREG		;save value. will be over-written by largest number
		jmp  _l2
_l1		dey
		beq  _l3
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  c64.SCRATCH_ZPWORD2+1
		bmi  _l1
_l2		sty  c64.SCRATCH_ZPWORD2	;index of potentially largest value
		sta  c64.SCRATCH_ZPWORD2+1	;potentially largest value
		jmp  _l1
_l3		ldy  c64.SCRATCH_ZPB1		;where the largest value shall be put
		lda  c64.SCRATCH_ZPWORD2+1	;the largest value
		sta  (c64.SCRATCH_ZPWORD1),y	;put largest value in place
		ldy  c64.SCRATCH_ZPWORD2	;index of free space
		lda  c64.SCRATCH_ZPREG		;the over-written value
		sta  (c64.SCRATCH_ZPWORD1),y	;put the over-written value in the free space
		dec  c64.SCRATCH_ZPB1		;end of the shorter sequence still left
		bne  _sortloop			;start working with the shorter sequence
		rts
		.pend


sort_uw		.proc
		; 16bit unsigned sort
		; sorting subroutine coded by mats rosengren (mats.rosengren@esa.int)
		; input:  address of array to sort in c64.SCRATCH_ZPWORD1, length in c64.SCRATCH_ZPB1
		; first: subtract 2 of the pointer
		asl  c64.SCRATCH_ZPB1		; *2 because words
		lda  c64.SCRATCH_ZPWORD1
		sec
		sbc  #2
		sta  c64.SCRATCH_ZPWORD1
		bcs  _sort_loop
		dec  c64.SCRATCH_ZPWORD1+1
_sort_loop	ldy  c64.SCRATCH_ZPB1    	;start of subroutine sort
		lda  (c64.SCRATCH_ZPWORD1),y    ;last value in (what is left of) sequence to be sorted
		sta  _work3          		;save value. will be over-written by largest number
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _work3+1
		dey
		jmp  _l2
_l1		dey
		dey
		beq  _l3
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		dey
		cmp  c64.SCRATCH_ZPWORD2+1
		bne  +
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  c64.SCRATCH_ZPWORD2
+		bcc  _l1
_l2		sty  _work1          		;index of potentially largest value
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  c64.SCRATCH_ZPWORD2          ;potentially largest value
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  c64.SCRATCH_ZPWORD2+1
		dey
		jmp  _l1
_l3		ldy  c64.SCRATCH_ZPB1           ;where the largest value shall be put
		lda  c64.SCRATCH_ZPWORD2          ;the largest value
		sta  (c64.SCRATCH_ZPWORD1),y      ;put largest value in place
		iny
		lda  c64.SCRATCH_ZPWORD2+1
		sta  (c64.SCRATCH_ZPWORD1),y
		ldy  _work1         		 ;index of free space
		lda  _work3          		;the over-written value
		sta  (c64.SCRATCH_ZPWORD1),y      ;put the over-written value in the free space
		iny
		lda  _work3+1
		sta  (c64.SCRATCH_ZPWORD1),y
		dey
		dec  c64.SCRATCH_ZPB1           ;end of the shorter sequence still left
		dec  c64.SCRATCH_ZPB1
		bne  _sort_loop           ;start working with the shorter sequence
		rts
_work1	.byte  0
_work3	.word  0
		.pend


sort_w		.proc
		; 16bit signed sort
		; sorting subroutine coded by mats rosengren (mats.rosengren@esa.int)
		; input:  address of array to sort in c64.SCRATCH_ZPWORD1, length in c64.SCRATCH_ZPB1
		; first: subtract 2 of the pointer
		asl  c64.SCRATCH_ZPB1		; *2 because words
		lda  c64.SCRATCH_ZPWORD1
		sec
		sbc  #2
		sta  c64.SCRATCH_ZPWORD1
		bcs  _sort_loop
		dec  c64.SCRATCH_ZPWORD1+1
_sort_loop	ldy  c64.SCRATCH_ZPB1    	;start of subroutine sort
		lda  (c64.SCRATCH_ZPWORD1),y    ;last value in (what is left of) sequence to be sorted
		sta  _work3          		;save value. will be over-written by largest number
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  _work3+1
		dey
		jmp  _l2
_l1		dey
		dey
		beq  _l3
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  c64.SCRATCH_ZPWORD2
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		dey
		sbc  c64.SCRATCH_ZPWORD2+1
		bvc  +
		eor  #$80
+		bmi  _l1
_l2		sty  _work1          		;index of potentially largest value
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  c64.SCRATCH_ZPWORD2          ;potentially largest value
		iny
		lda  (c64.SCRATCH_ZPWORD1),y
		sta  c64.SCRATCH_ZPWORD2+1
		dey
		jmp  _l1
_l3		ldy  c64.SCRATCH_ZPB1           ;where the largest value shall be put
		lda  c64.SCRATCH_ZPWORD2          ;the largest value
		sta  (c64.SCRATCH_ZPWORD1),y      ;put largest value in place
		iny
		lda  c64.SCRATCH_ZPWORD2+1
		sta  (c64.SCRATCH_ZPWORD1),y
		ldy  _work1         		 ;index of free space
		lda  _work3          		;the over-written value
		sta  (c64.SCRATCH_ZPWORD1),y      ;put the over-written value in the free space
		iny
		lda  _work3+1
		sta  (c64.SCRATCH_ZPWORD1),y
		dey
		dec  c64.SCRATCH_ZPB1           ;end of the shorter sequence still left
		dec  c64.SCRATCH_ZPB1
		bne  _sort_loop           ;start working with the shorter sequence
		rts
_work1	.byte  0
_work3	.word  0
		.pend


reverse_b	.proc
		; --- reverse an array of bytes (in-place)
		; inputs:  pointer to array in c64.SCRATCH_ZPWORD1, length in A
_left_index = c64.SCRATCH_ZPWORD2
_right_index = c64.SCRATCH_ZPWORD2+1
		pha
		sec
		sbc  #1
		sta  _left_index
		lda  #0
		sta  _right_index
		pla
		lsr  a
		tay
_loop		sty  c64.SCRATCH_ZPREG
		ldy  _left_index
		lda  (c64.SCRATCH_ZPWORD1),y
		pha
		ldy  _right_index
		lda  (c64.SCRATCH_ZPWORD1),y
		ldy  _left_index
		sta  (c64.SCRATCH_ZPWORD1),y
		pla
		ldy  _right_index
		sta  (c64.SCRATCH_ZPWORD1),y
		inc  _right_index
		dec  _left_index
		ldy  c64.SCRATCH_ZPREG
		dey
		bne  _loop
		rts
		.pend


reverse_w	.proc
		; --- reverse an array of words (in-place)
		; inputs:  pointer to array in c64.SCRATCH_ZPWORD1, length in A
_left_index = c64.SCRATCH_ZPWORD2
_right_index = c64.SCRATCH_ZPWORD2+1
		pha
		asl  a     ; *2 because words
		sec
		sbc  #2
		sta  _left_index
		lda  #0
		sta  _right_index
		pla
		lsr  a
		pha
		tay
		; first reverse the lsbs
_loop_lo	sty  c64.SCRATCH_ZPREG
		ldy  _left_index
		lda  (c64.SCRATCH_ZPWORD1),y
		pha
		ldy  _right_index
		lda  (c64.SCRATCH_ZPWORD1),y
		ldy  _left_index
		sta  (c64.SCRATCH_ZPWORD1),y
		pla
		ldy  _right_index
		sta  (c64.SCRATCH_ZPWORD1),y
		inc  _right_index
		inc  _right_index
		dec  _left_index
		dec  _left_index
		ldy  c64.SCRATCH_ZPREG
		dey
		bne  _loop_lo
		; now reverse the msbs
		dec  _right_index
		inc  _left_index
		inc  _left_index
		inc  _left_index
		pla
		tay
_loop_hi	sty  c64.SCRATCH_ZPREG
		ldy  _left_index
		lda  (c64.SCRATCH_ZPWORD1),y
		pha
		ldy  _right_index
		lda  (c64.SCRATCH_ZPWORD1),y
		ldy  _left_index
		sta  (c64.SCRATCH_ZPWORD1),y
		pla
		ldy  _right_index
		sta  (c64.SCRATCH_ZPWORD1),y
		dec  _right_index
		dec  _right_index
		inc  _left_index
		inc  _left_index
		ldy  c64.SCRATCH_ZPREG
		dey
		bne  _loop_hi

		rts
		.pend

ror2_mem_ub	.proc
		; -- in-place 8-bit ror of byte at memory location on stack
		inx
		lda  c64.ESTACK_LO,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI,x
		sta  c64.SCRATCH_ZPWORD1+1
		ldy  #0
		lda  (c64.SCRATCH_ZPWORD1),y
		lsr  a
		bcc  +
		ora  #$80
+		sta  (c64.SCRATCH_ZPWORD1),y
		rts
		.pend

rol2_mem_ub	.proc
		; -- in-place 8-bit rol of byte at memory location on stack
		;"  lda  ${number.toHex()} |  cmp  #\$80 |  rol  a |  sta  ${number.toHex()}"
		inx
		lda  c64.ESTACK_LO,x
		sta  c64.SCRATCH_ZPWORD1
		lda  c64.ESTACK_HI,x
		sta  c64.SCRATCH_ZPWORD1+1
		ldy  #0
		lda  (c64.SCRATCH_ZPWORD1),y
		cmp  #$80
		rol  a
		sta  (c64.SCRATCH_ZPWORD1),y
		rts
		.pend

lsl_array_b	.proc
		.warn "lsl_array_b"		; TODO
		.pend

lsl_array_w	.proc
		.warn "lsl_array_w"		; TODO
		.pend

lsr_array_ub	.proc
		.warn "lsr_array_ub"		; TODO
		.pend

lsr_array_b	.proc
		.warn "lsr_array_b"		; TODO
		.pend

lsr_array_uw	.proc
		.warn "lsr_array_uw"		; TODO
		.pend

lsr_array_w	.proc
		.warn "lsr_array_w"		; TODO
		.pend

rol_array_ub	.proc
		.warn "rol_array_ub"		; TODO
		.pend

rol_array_uw	.proc
		.warn "rol_array_uw"		; TODO
		.pend

rol2_array_ub	.proc
		.warn "rol2_array_ub"		; TODO
		.pend

rol2_array_uw	.proc
		.warn "rol2_array_uw"		; TODO
		.pend

ror_array_ub	.proc
		.warn "ror_array_ub"		; TODO
		.pend

ror_array_uw	.proc
		.warn "ror_array_uw"		; TODO
		.pend

ror2_array_ub	.proc
		.warn "ror2_array_ub"		; TODO
		.pend

ror2_array_uw	.proc
		.warn "ror2_array_uw"		; TODO
		.pend
