; Prog8 definitions for the Commodore-64
; Including memory registers, I/O registers, Basic and Kernal subroutines, utility subroutines.
;
; Written by Irmen de Jong (irmen@razorvine.net) - license: GNU GPL 3.0
; ;
; indent format: TABS, size=8

%option enable_floats


~ c64 {
		memory  byte  SCRATCH_ZP1	= $02		; scratch register #1 in ZP
		memory  byte  SCRATCH_ZP2	= $03		; scratch register #2 in ZP
		memory  word  SCRATCH_ZPWORD1	= $fb		; scratch word in ZP ($fb/$fc)
		memory  word  SCRATCH_ZPWORD2	= $fd		; scratch word in ZP ($fd/$fe)


		memory  byte  TIME_HI		= $a0		; software jiffy clock, hi byte
		memory  byte  TIME_MID		= $a1		;  .. mid byte
		memory  byte  TIME_LO		= $a2		;    .. lo byte. Updated by IRQ every 1/60 sec
		memory  byte  STKEY		= $91		; various keyboard statuses (updated by IRQ)
		memory  byte  SFDX		= $cb		; current key pressed (matrix value) (updated by IRQ)

		memory  byte  COLOR		= $0286		; cursor color
		memory  byte  HIBASE		= $0288         ; screen base address / 256 (hi-byte of screen memory address)
		memory  word  CINV		= $0314		; IRQ vector
		memory  word  NMI_VEC		= $FFFA  	; 6502 nmi vector, determined by the kernal if banked in
		memory  word  RESET_VEC		= $FFFC  	; 6502 reset vector, determined by the kernal if banked in
		memory  word  IRQ_VEC		= $FFFE  	; 6502 interrupt vector, determined by the kernal if banked in

		memory  byte[40, 25]  Screen	= $0400		; default character screen matrix
		memory  byte[40, 25]  Colors	= $d800		; character screen colors


; ---- VIC-II registers ----

		memory  byte SP0X		= $d000
		memory  byte SP0Y		= $d001
		memory  byte SP1X		= $d002
		memory  byte SP1Y		= $d003
		memory  byte SP2X		= $d004
		memory  byte SP2Y		= $d005
		memory  byte SP3X		= $d006
		memory  byte SP3Y		= $d007
		memory  byte SP4X		= $d008
		memory  byte SP4Y		= $d009
		memory  byte SP5X		= $d00a
		memory  byte SP5Y		= $d00b
		memory  byte SP6X		= $d00c
		memory  byte SP6Y		= $d00d
		memory  byte SP7X		= $d00e
		memory  byte SP7Y		= $d00f

		memory  byte MSIGX		= $d010
		memory  byte SCROLY		= $d011
		memory  byte RASTER		= $d012
		memory  byte LPENX		= $d013
		memory  byte LPENY		= $d014
		memory  byte SPENA		= $d015
		memory  byte SCROLX		= $d016
		memory  byte YXPAND		= $d017
		memory  byte VMCSB		= $d018
		memory  byte VICIRQ		= $d019
		memory  byte IREQMASK		= $d01a
		memory  byte SPBGPR		= $d01b
		memory  byte SPMC		= $d01c
		memory  byte XXPAND		= $d01d
		memory  byte SPSPCL		= $d01e
		memory  byte SPBGCL		= $d01f

		memory  byte EXTCOL		= $d020		; border color
		memory  byte BGCOL0		= $d021		; screen color
		memory  byte BGCOL1		= $d022
		memory  byte BGCOL2		= $d023
		memory  byte BGCOL4		= $d024
		memory  byte SPMC0		= $d025
		memory  byte SPMC1		= $d026
		memory  byte SP0COL		= $d027
		memory  byte SP1COL		= $d028
		memory  byte SP2COL		= $d029
		memory  byte SP3COL		= $d02a
		memory  byte SP4COL		= $d02b
		memory  byte SP5COL		= $d02c
		memory  byte SP6COL		= $d02d
		memory  byte SP7COL		= $d02e

; ---- end of VIC-II registers ----

; ---- C64 basic and kernal ROM float constants and functions ----

		; note: the fac1 and fac2 are working registers and take 6 bytes each,
		; floats in memory  (and rom) are stored in 5-byte MFLPT packed format.

		; constants in five-byte "mflpt" format in the BASIC ROM
		memory  float  FL_PIVAL		= $aea8  ; 3.1415926...
		memory  float  FL_N32768	= $b1a5  ; -32768
		memory  float  FL_FONE		= $b9bc  ; 1
		memory  float  FL_SQRHLF	= $b9d6  ; SQR(2) / 2
		memory  float  FL_SQRTWO	= $b9db  ; SQR(2)
		memory  float  FL_NEGHLF	= $b9e0  ; -.5
		memory  float  FL_LOG2		= $b9e5  ; LOG(2)
		memory  float  FL_TENC		= $baf9  ; 10
		memory  float  FL_NZMIL		= $bdbd  ; 1e9 (1 billion)
		memory  float  FL_FHALF		= $bf11  ; .5
		memory  float  FL_LOGEB2	= $bfbf  ; 1 / LOG(2)
		memory  float  FL_PIHALF	= $e2e0  ; PI / 2
		memory  float  FL_TWOPI		= $e2e5  ; 2 * PI
		memory  float  FL_FR4		= $e2ea  ; .25


; note: fac1/2 might get clobbered even if not mentioned in the function's name.
; note: for subtraction and division, the left operand is in fac2, the right operand in fac1.

; checked functions below:
sub	MOVFM		(mflpt: AY) -> (A?, Y?)	= $bba2		; load mflpt value from memory  in A/Y into fac1
sub	FREADMEM	() -> (A?, Y?)		= $bba6		; load mflpt value from memory  in $22/$23 into fac1
sub	CONUPK		(mflpt: AY) -> (A?, Y?)	= $ba8c		; load mflpt value from memory  in A/Y into fac2
sub	FAREADMEM	() -> (A?, Y?)		= $ba90		; load mflpt value from memory  in $22/$23 into fac2
sub	MOVFA		() -> (A?, X?)		= $bbfc		; copy fac2 to fac1
sub	MOVAF		() -> (A?, X?)		= $bc0c		; copy fac1 to fac2  (rounded)
sub	MOVEF		() -> (A?, X?)		= $bc0f		; copy fac1 to fac2
sub	FTOMEMXY	(mflpt: XY) -> (A?, Y?)	= $bbd4		; store fac1 to memory  X/Y as 5-byte mflpt

; fac1-> signed word in Y/A (might throw ILLEGAL QUANTITY)
; (use c64flt.FTOSWRDAY to get A/Y output; lo/hi switched to normal order)
sub	FTOSWORDYA	() -> (Y, A, X?)	= $b1aa

; fac1 -> unsigned word in Y/A (might throw ILLEGAL QUANTITY) (result also in $14/15)
; (use c64flt.GETADRAY to get A/Y output; lo/hi switched to normal order)
sub	GETADR		() -> (Y, A, X?)	= $b7f7

sub	QINT		() -> (?)		= $bc9b		; fac1 -> 4-byte signed integer in 98-101 ($62-$65), with the MSB FIRST.
sub	AYINT		() -> (?)		= $b1bf		; fac1-> signed word in 100-101 ($64-$65) MSB FIRST. (might throw ILLEGAL QUANTITY)

; signed word in Y/A -> float in fac1
; (use c64flt.GIVAYFAY to use A/Y input; lo/hi switched to normal order)
; there is also c64flt.GIVUAYF - unsigned word in A/Y (lo/hi) to fac1
; there is also c64flt.FREADS32  that reads from 98-101 ($62-$65) MSB FIRST
; there is also c64flt.FREADUS32  that reads from 98-101 ($62-$65) MSB FIRST
; there is also c64flt.FREADS24AXY  that reads signed int24 into fac1 from A/X/Y (lo/mid/hi bytes)
sub	GIVAYF		(lo: Y, hi: A) -> (?)	= $b391

sub	FREADUY		(ubyte: Y) -> (?)	= $b3a2		; 8 bit unsigned Y -> float in fac1
sub	FREADSA		(sbyte: A) -> (?)	= $bc3c		; 8 bit signed A -> float in fac1
sub	FREADSTR	(length: A) -> (?)	= $b7b5		; str -> fac1, $22/23 must point to string, A=string length
sub	FPRINTLN	() -> (?)		= $aabc		; print string of fac1, on one line (= with newline)
sub	FOUT		() -> (AY, X?)		= $bddd		; fac1 -> string, address returned in AY ($0100)

sub	FADDH		() -> (?)		= $b849		; fac1 += 0.5, for rounding- call this before INT
sub	MUL10		() -> (?)		= $bae2		; fac1 *= 10
sub	DIV10		() -> (?)		= $bafe		; fac1 /= 10 , CAUTION: result is always positive!
sub	FCOMP		(mflpt: AY) -> (A, X?, Y?)	= $bc5b		; A = compare fac1 to mflpt in A/Y, 0=equal 1=fac1 is greater, 255=fac1 is less than

sub	FADDT		() -> (?)		= $b86a		; fac1 += fac2
sub	FADD		(mflpt: AY) -> (?)	= $b867		; fac1 += mflpt value from A/Y
sub	FSUBT		() -> (?)		= $b853		; fac1 = fac2-fac1   mind the order of the operands
sub	FSUB		(mflpt: AY) -> (?)	= $b850		; fac1 = mflpt from A/Y - fac1
sub	FMULTT 		() -> (?)		= $ba2b		; fac1 *= fac2
sub	FMULT		(mflpt: AY) -> (?)	= $ba28		; fac1 *= mflpt value from A/Y
sub	FDIVT 		() -> (?)		= $bb12		; fac1 = fac2/fac1   mind the order of the operands
sub	FDIV  		(mflpt: AY) -> (?)	= $bb0f		; fac1 = mflpt in A/Y / fac1
sub	FPWRT		() -> (?)		= $bf7b		; fac1 = fac2 ** fac1
sub	FPWR		(mflpt: AY) -> (?)	= $bf78		; fac1 = fac2 ** mflpt from A/Y

sub	NOTOP		() -> (?)		= $aed4		; fac1 = NOT(fac1)
sub	INT		() -> (?)		= $bccc		; INT() truncates, use FADDH first to round instead of trunc
sub	LOG		() -> (?)		= $b9ea		; fac1 = LN(fac1)  (natural log)
sub	SGN		() -> (?)		= $bc39		; fac1 = SGN(fac1), result of SIGN (-1, 0 or 1)
sub	SIGN		() -> (A)		= $bc2b		; SIGN(fac1) to A, $ff, $0, $1 for negative, zero, positive
sub	ABS		() -> ()		= $bc58		; fac1 = ABS(fac1)
sub	SQR		() -> (?)		= $bf71		; fac1 = SQRT(fac1)
sub	EXP		() -> (?)		= $bfed		; fac1 = EXP(fac1)  (e ** fac1)
sub	NEGOP		() -> (A?)		= $bfb4		; switch the sign of fac1
sub	RND		() -> (?)		= $e097		; fac1 = RND()   (use RNDA instead)
sub	RNDA		(acc: A) -> (?)		= $e09a		; fac1 = RND(A)
sub	COS		() -> (?)		= $e264		; fac1 = COS(fac1)
sub	SIN		() -> (?)		= $e26b		; fac1 = SIN(fac1)
sub	TAN		() -> (?)		= $e2b4		; fac1 = TAN(fac1)
sub	ATN		() -> (?)		= $e30e		; fac1 = ATN(fac1)


; ---- C64 basic routines ----

sub	CLEARSCR	() -> (?)		= $E544		; clear the screen
sub	HOMECRSR	() -> (?)		= $E566		; cursor to top left of screen


; ---- end of C64 basic routines ----



; ---- C64 kernal routines ----

sub	IRQDFRT  () -> (?)			= $EA31		; default IRQ routine
sub	IRQDFEND () -> (?)			= $EA81		; default IRQ end/cleanup
sub	CINT     () -> (?)			= $FF81		; (alias: SCINIT) initialize screen editor and video chip
sub	IOINIT   () -> (A?, X?)			= $FF84		; initialize I/O devices (CIA, SID, IRQ)
sub	RAMTAS   () -> (?)			= $FF87		; initialize RAM, tape buffer, screen
sub	RESTOR   () -> (?)			= $FF8A		; restore default I/O vectors
sub	VECTOR   (dir: Pc, userptr: XY) -> (A?, Y?)	= $FF8D		; read/set I/O vector table
sub	SETMSG   (value: A) -> ()		= $FF90		; set Kernal message control flag
sub	SECOND   (address: A) -> (A?)		= $FF93		; (alias: LSTNSA) send secondary address after LISTEN
sub	TKSA     (address: A) -> (A?)		= $FF96		; (alias: TALKSA) send secondary address after TALK
sub	MEMTOP   (dir: Pc, address: XY) -> (XY)	= $FF99		; read/set top of memory  pointer
sub	MEMBOT   (dir: Pc, address: XY) -> (XY)	= $FF9C		; read/set bottom of memory  pointer
sub	SCNKEY   () -> (?)			= $FF9F		; scan the keyboard
sub	SETTMO   (timeout: A) -> ()		= $FFA2		; set time-out flag for IEEE bus
sub	ACPTR    () -> (A)			= $FFA5		; (alias: IECIN) input byte from serial bus
sub	CIOUT    (databyte: A) -> ()		= $FFA8		; (alias: IECOUT) output byte to serial bus
sub	UNTLK    () -> (A?)			= $FFAB		; command serial bus device to UNTALK
sub	UNLSN    () -> (A?)			= $FFAE		; command serial bus device to UNLISTEN
sub	LISTEN   (device: A) -> (A?)		= $FFB1		; command serial bus device to LISTEN
sub	TALK     (device: A) -> (A?)		= $FFB4		; command serial bus device to TALK
sub	READST   () -> (A)			= $FFB7		; read I/O status word
sub	SETLFS   (logical: A, device: X, address: Y) -> () = $FFBA	; set logical file parameters
sub	SETNAM   (namelen: A, filename: XY) -> ()	= $FFBD		; set filename parameters
sub	OPEN     () -> (?)			= $FFC0		; (via 794 ($31A)) open a logical file
sub	CLOSE    (logical: A) -> (?)		= $FFC3		; (via 796 ($31C)) close a logical file
sub	CHKIN    (logical: X) -> (A?, X?)	= $FFC6		; (via 798 ($31E)) define an input channel
sub	CHKOUT   (logical: X) -> (A?, X?)	= $FFC9		; (via 800 ($320)) define an output channel
sub	CLRCHN   () -> (A?, X?)			= $FFCC		; (via 802 ($322)) restore default devices
sub	CHRIN    () -> (A, Y?)			= $FFCF		; (via 804 ($324)) input a character (for keyboard, read a whole line from the screen) A=byte read.
sub	CHROUT   (char: A) -> ()		= $FFD2		; (via 806 ($326)) output a character
sub	LOAD     (verify: A, address: XY) -> (Pc, A, X, Y) = $FFD5	; (via 816 ($330)) load from device
sub	SAVE     (zp_startaddr: A, endaddr: XY) -> (Pc, A) = $FFD8	; (via 818 ($332)) save to a device
sub	SETTIM   (low: A, middle: X, high: Y) -> ()	= $FFDB		; set the software clock
sub	RDTIM    () -> (A, X, Y)		= $FFDE		; read the software clock
sub	STOP     () -> (Pz, Pc, A?, X?)		= $FFE1		; (via 808 ($328)) check the STOP key
sub	GETIN    () -> (A, X?, Y?)		= $FFE4		; (via 810 ($32A)) get a character
sub	CLALL    () -> (A?, X?)			= $FFE7		; (via 812 ($32C)) close all files
sub	UDTIM    () -> (A?, X?)			= $FFEA		; update the software clock
sub	SCREEN   () -> (X, Y)			= $FFED		; read number of screen rows and columns
sub	PLOT     (dir: Pc, col: Y, row: X) -> (X, Y)	= $FFF0		; read/set position of cursor on screen
sub	IOBASE   () -> (X, Y)			= $FFF3		; read base address of I/O devices

; ---- end of C64 kernal routines ----



; ----- utility functions ----

sub  init_system  () -> (?)  {
	; ---- initializes the machine to a sane starting state
	; This means that the BASIC, KERNAL and CHARGEN ROMs are banked in,
	; the VIC, SID and CIA chips are reset, screen is cleared, and the default IRQ is set.
	; Also a different color scheme is chosen to identify ourselves a little.
	%asm {{
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
	}}
}

}  ; ------ end of block c64


~ c64flt {
	; ---- this block contains C-64 floating point related functions ----


sub  FREADS32  () -> (?)  {
	; ---- fac1 = signed int32 from $62-$65 big endian (MSB FIRST)
	%asm {{
		lda  $62
		eor  #$ff
		asl  a
		lda  #0
		ldx  #$a0
		jmp  $bc4f		; internal BASIC routine
	}}
}

sub  FREADUS32  () -> (?)  {
	; ---- fac1 = uint32 from $62-$65 big endian (MSB FIRST)
	%asm {{
		sec
		lda  #0
		ldx  #$a0
		jmp  $bc4f		; internal BASIC routine
	}}
}

sub  FREADS24AXY  (lo: A, mid: X, hi: Y) -> (?)  {
	; ---- fac1 = signed int24 (A/X/Y contain lo/mid/hi bytes)
	;      note: there is no FREADU24AXY (unsigned), use FREADUS32 instead.
	%asm {{
		sty  $62
		stx  $63
		sta  $64
		lda  $62
		eor  #$FF
		asl  a
		lda  #0
		sta  $65
		ldx  #$98
		jmp  $bc4f		; internal BASIC routine
	}}
}

sub  GIVUAYF  (uword: AY) -> (?)  {
	; ---- unsigned 16 bit word in A/Y (lo/hi) to fac1
	%asm {{
		sty  $62
		sta  $63
		ldx  #$90
		sec
		jmp  $bc49		; internal BASIC routine
	}}
}

sub  GIVAYFAY  (sword: AY) -> (?)  {
	; ---- signed 16 bit word in A/Y (lo/hi) to float in fac1
	%asm {{
		sta  c64.SCRATCH_ZP1
		tya
		ldy  c64.SCRATCH_ZP1
		jmp  c64.GIVAYF		; this uses the inverse order, Y/A
	}}
}

sub  FTOSWRDAY  () -> (AY, X?)  {
	; ---- fac1 to signed word in A/Y
	%asm {{
		jsr  c64.FTOSWORDYA	; note the inverse Y/A order
		sta  c64.SCRATCH_ZP1
		tya
		ldy  c64.SCRATCH_ZP1
		rts
	}}
}

sub  GETADRAY  () -> (AY, X?)  {
	; ---- fac1 to unsigned word in A/Y
	%asm {{
		jsr  c64.GETADR		; this uses the inverse order, Y/A
		sta  c64.SCRATCH_ZP1
		tya
		ldy  c64.SCRATCH_ZP1
		rts
	}}
}


sub  copy_mflt  (source: XY) -> (A?, Y?)  {
	; ---- copy a 5 byte MFLT floating point variable to another place
	;      input: X/Y = source address,  c64.SCRATCH_ZPWORD1 = destination address
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZPWORD1+1
		ldy  #0
		lda  (c64.SCRATCH_ZP1),y
		sta  (c64.SCRATCH_ZPWORD1),y
		iny
		lda  (c64.SCRATCH_ZP1),y
		sta  (c64.SCRATCH_ZPWORD1),y
		iny
		lda  (c64.SCRATCH_ZP1),y
		sta  (c64.SCRATCH_ZPWORD1),y
		iny
		lda  (c64.SCRATCH_ZP1),y
		sta  (c64.SCRATCH_ZPWORD1),y
		iny
		lda  (c64.SCRATCH_ZP1),y
		sta  (c64.SCRATCH_ZPWORD1),y
		ldy  c64.SCRATCH_ZPWORD1+1
		rts
	}}
}

sub  float_add_one  (mflt: XY) -> (?)  {
	; ---- add 1 to the MFLT pointed to by X/Y.  Clobbers A, X, Y
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZP2
		txa
		jsr  c64.MOVFM		; fac1 = float XY
		lda  #<c64.FL_FONE
		ldy  #>c64.FL_FONE
		jsr  c64.FADD		; fac1 += 1
		ldx  c64.SCRATCH_ZP1
		ldy  c64.SCRATCH_ZP2
		jmp  c64.FTOMEMXY	; float XY = fac1
	}}
}

sub  float_sub_one  (mflt: XY) -> (?)  {
	; ---- subtract 1 from the MFLT pointed to by X/Y.  Clobbers A, X, Y
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZP2
		lda  #<c64.FL_FONE
		ldy  #>c64.FL_FONE
		jsr  c64.MOVFM		; fac1 = 1
		txa
		ldy  c64.SCRATCH_ZP2
		jsr  c64.FSUB		; fac1 = float XY - 1
		ldx  c64.SCRATCH_ZP1
		ldy  c64.SCRATCH_ZP2
		jmp  c64.FTOMEMXY	; float XY = fac1
	}}
}

sub  float_add_SW1_to_XY  (mflt: XY) -> (?)  {
	; ---- add MFLT pointed to by SCRATCH_ZPWORD1 to the MFLT pointed to by X/Y.  Clobbers A, X, Y
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZP2
		txa
		jsr  c64.MOVFM		; fac1 = float XY
		lda  c64.SCRATCH_ZPWORD1
		ldy  c64.SCRATCH_ZPWORD1+1
		jsr  c64.FADD		; fac1 += SCRATCH_ZPWORD1
		ldx  c64.SCRATCH_ZP1
		ldy  c64.SCRATCH_ZP2
		jmp  c64.FTOMEMXY	; float XY = fac1
	}}
}

sub  float_sub_SW1_from_XY  (mflt: XY) -> (?)  {
	; ---- subtract MFLT pointed to by SCRATCH_ZPWORD1 from the MFLT pointed to by X/Y.  Clobbers A, X, Y
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZP2
		lda  c64.SCRATCH_ZPWORD1
		ldy  c64.SCRATCH_ZPWORD1+1
		jsr  c64.MOVFM		; fac1 = SCRATCH_ZPWORD1
		txa
		ldy  c64.SCRATCH_ZP2
		jsr  c64.FSUB		; fac1 = float XY - SCRATCH_ZPWORD1
		ldx  c64.SCRATCH_ZP1
		ldy  c64.SCRATCH_ZP2
		jmp  c64.FTOMEMXY	; float XY = fac1
	}}
}

}  ; ------ end of block c64flt



~ c64scr {
	; ---- this block contains (character) Screen and text I/O related functions ----


sub  clear_screen (char: A, color: Y) -> ()  {
	; ---- clear the character screen with the given fill character and character color.
	;      (assumes screen is at $0400, could be altered in the future with self-modifying code)
	;       @todo some byte var to set the SCREEN ADDR HI BYTE

	%asm {{
		sta  _loop + 1      ; self-modifying
		stx  c64.SCRATCH_ZP1
		ldx  #0
_loop           lda  #0
		sta  c64.Screen,x
		sta  c64.Screen+$0100,x
		sta  c64.Screen+$0200,x
		sta  c64.Screen+$02e8,x
	        tya
		sta  c64.Colors,x
		sta  c64.Colors+$0100,x
		sta  c64.Colors+$0200,x
		sta  c64.Colors+$02e8,x
		inx
		bne  _loop

		lda  _loop+1		; restore A and X
		ldx  c64.SCRATCH_ZP1
		rts
        }}

}


sub scroll_left_full  (alsocolors: Pc) -> (A?, X?, Y?)  {
	; ---- scroll the whole screen 1 character to the left
	;      contents of the rightmost column are unchanged, you should clear/refill this yourself
	;      Carry flag determines if screen color data must be scrolled too
	%asm {{
		bcs  +
		jmp  _scroll_screen

+               ; scroll the color memory
		ldx  #0
		ldy  #38
-
	.for row=0, row<=12, row+=1
		lda  c64.Colors + 40*row + 1,x
		sta  c64.Colors + 40*row,x
	.next
		inx
		dey
		bpl  -

		ldx  #0
		ldy  #38
-
	.for row=13, row<=24, row+=1
		lda  c64.Colors + 40*row + 1,x
		sta  c64.Colors + 40*row,x
	.next
		inx
		dey
		bpl  -

_scroll_screen  ; scroll the screen memory
		ldx  #0
		ldy  #38
-
	.for row=0, row<=12, row+=1
		lda  c64.Screen + 40*row + 1,x
		sta  c64.Screen + 40*row,x
	.next
		inx
		dey
		bpl  -

		ldx  #0
		ldy  #38
-
	.for row=13, row<=24, row+=1
		lda  c64.Screen + 40*row + 1,x
		sta  c64.Screen + 40*row,x
	.next
		inx
		dey
		bpl  -

		rts
	}}
}


sub scroll_right_full  (alsocolors: Pc) -> (A?, X?)  {
	; ---- scroll the whole screen 1 character to the right
	;      contents of the leftmost column are unchanged, you should clear/refill this yourself
	;      Carry flag determines if screen color data must be scrolled too
	%asm {{
		bcs  +
		jmp  _scroll_screen

+               ; scroll the color memory
		ldx  #38
-
	.for row=0, row<=12, row+=1
		lda  c64.Colors + 40*row + 0,x
		sta  c64.Colors + 40*row + 1,x
	.next
		dex
		bpl  -

		ldx  #38
-
	.for row=13, row<=24, row+=1
		lda  c64.Colors + 40*row,x
		sta  c64.Colors + 40*row + 1,x
	.next
		dex
		bpl  -

_scroll_screen  ; scroll the screen memory
		ldx  #38
-
	.for row=0, row<=12, row+=1
		lda  c64.Screen + 40*row + 0,x
		sta  c64.Screen + 40*row + 1,x
	.next
		dex
		bpl  -

		ldx  #38
-
	.for row=13, row<=24, row+=1
		lda  c64.Screen + 40*row,x
		sta  c64.Screen + 40*row + 1,x
	.next
		dex
		bpl  -

		rts
	}}
}


sub scroll_up_full  (alsocolors: Pc) -> (A?, X?)  {
	; ---- scroll the whole screen 1 character up
	;      contents of the bottom row are unchanged, you should refill/clear this yourself
	;      Carry flag determines if screen color data must be scrolled too
	%asm {{
		bcs  +
		jmp  _scroll_screen

+               ; scroll the color memory
		ldx #39
-
	.for row=1, row<=11, row+=1
		lda  c64.Colors + 40*row,x
		sta  c64.Colors + 40*(row-1),x
	.next
		dex
		bpl  -

		ldx #39
-
	.for row=12, row<=24, row+=1
		lda  c64.Colors + 40*row,x
		sta  c64.Colors + 40*(row-1),x
	.next
		dex
		bpl  -

_scroll_screen  ; scroll the screen memory
		ldx #39
-
	.for row=1, row<=11, row+=1
		lda  c64.Screen + 40*row,x
		sta  c64.Screen + 40*(row-1),x
	.next
		dex
		bpl  -

		ldx #39
-
	.for row=12, row<=24, row+=1
		lda  c64.Screen + 40*row,x
		sta  c64.Screen + 40*(row-1),x
	.next
		dex
		bpl  -

		rts
	}}
}


sub scroll_down_full  (alsocolors: Pc) -> (A?, X?)  {
	; ---- scroll the whole screen 1 character down
	;      contents of the top row are unchanged, you should refill/clear this yourself
	;      Carry flag determines if screen color data must be scrolled too
	%asm {{
		bcs  +
		jmp  _scroll_screen

+               ; scroll the color memory
		ldx #39
-
	.for row=23, row>=12, row-=1
		lda  c64.Colors + 40*row,x
		sta  c64.Colors + 40*(row+1),x
	.next
		dex
		bpl  -

		ldx #39
-
	.for row=11, row>=0, row-=1
		lda  c64.Colors + 40*row,x
		sta  c64.Colors + 40*(row+1),x
	.next
		dex
		bpl  -

_scroll_screen  ; scroll the screen memory
		ldx #39
-
	.for row=23, row>=12, row-=1
		lda  c64.Screen + 40*row,x
		sta  c64.Screen + 40*(row+1),x
	.next
		dex
		bpl  -

		ldx #39
-
	.for row=11, row>=0, row-=1
		lda  c64.Screen + 40*row,x
		sta  c64.Screen + 40*(row+1),x
	.next
		dex
		bpl  -

		rts
	}}
}


sub  byte2decimal  (ubyte: A) -> (Y, X, A)  {
	; ---- A to decimal string in Y/X/A  (100s in Y, 10s in X, 1s in A)
	%asm {{
		ldy  #$2f
		ldx  #$3a
		sec
-               iny
		sbc  #100
		bcs  -
-               dex
		adc  #10
		bmi  -
		adc  #$2f
		rts
	}}
}

sub  byte2hex  (ubyte: A) -> (X, Y, A?)  {
	; ---- A to hex string in XY (first hex char in X, second hex char in Y)
	%asm {{
		pha
		and  #$0f
		tax
		ldy  hex_digits,x
		pla
		lsr  a
		lsr  a
		lsr  a
		lsr  a
		tax
		lda  hex_digits,x
		tax
		rts

hex_digits	.str "0123456789abcdef"	; can probably be reused for other stuff as well
	}}
}


		str  word2hex_output = "1234"   ; 0-terminated, to make printing easier
sub  word2hex  (dataword: XY) -> (?)  {
	; ---- convert 16 bit word in X/Y into 4-character hexadecimal string into memory  'word2hex_output'
	%asm {{
		stx  c64.SCRATCH_ZP2
		tya
		jsr  byte2hex
		stx  word2hex_output
		sty  word2hex_output+1
		lda  c64.SCRATCH_ZP2
		jsr  byte2hex
		stx  word2hex_output+2
		sty  word2hex_output+3
		rts
	}}
}

		byte[3]  word2bcd_bcdbuff = [0, 0, 0]
sub  word2bcd  (dataword: XY) -> (A?, X?)  {
	; Convert an 16 bit binary value to BCD
	;
	; This function converts a 16 bit binary value in X/Y into a 24 bit BCD. It
	; works by transferring one bit a time from the source and adding it
	; into a BCD value that is being doubled on each iteration. As all the
	; arithmetic is being done in BCD the result is a binary to decimal
	; conversion.
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZP2
		sed				; switch to decimal mode
		lda  #0				; ensure the result is clear
		sta  word2bcd_bcdbuff+0
		sta  word2bcd_bcdbuff+1
		sta  word2bcd_bcdbuff+2
		ldx  #16			; the number of source bits

-		asl  c64.SCRATCH_ZP1		; shift out one bit
		rol  c64.SCRATCH_ZP2
		lda  word2bcd_bcdbuff+0		; and add into result
		adc  word2bcd_bcdbuff+0
		sta  word2bcd_bcdbuff+0
		lda  word2bcd_bcdbuff+1		; propagating any carry
		adc  word2bcd_bcdbuff+1
		sta  word2bcd_bcdbuff+1
		lda  word2bcd_bcdbuff+2		; ... thru whole result
		adc  word2bcd_bcdbuff+2
		sta  word2bcd_bcdbuff+2
		dex				; and repeat for next bit
		bne  -
		cld				; back to binary
		rts
	}}
}


		byte[5]  word2decimal_output = 0
sub  word2decimal  (dataword: XY) -> (?)  {
	; ---- convert 16 bit word in X/Y into decimal string into memory  'word2decimal_output'
	%asm {{
		jsr  word2bcd
		lda  word2bcd_bcdbuff+2
		clc
		adc  #'0'
		sta  word2decimal_output
		ldy  #1
		lda  word2bcd_bcdbuff+1
		jsr  +
		lda  word2bcd_bcdbuff+0

+		pha
		lsr  a
		lsr  a
		lsr  a
		lsr  a
		clc
		adc  #'0'
		sta  word2decimal_output,y
		iny
		pla
		and  #$0f
		adc  #'0'
		sta  word2decimal_output,y
		iny
		rts
	}}
}


; @todo string to 32 bit unsigned integer http://www.6502.org/source/strings/ascii-to-32bit.html


sub  print_string (address: XY) -> (A?, Y?)  {
	; ---- print null terminated string from X/Y
	; note: the compiler contains an optimization that will replace
	;       a call to this subroutine with a string argument of just one char,
	;       by just one call to c64.CHROUT of that single char.
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZP2
		ldy  #0
-               lda  (c64.SCRATCH_ZP1),y
		beq  +
		jsr  c64.CHROUT
		iny
		bne  -
+		rts
	}}
}


sub  print_pstring  (address: XY) -> (A?, X?, Y)  {
	; ---- print pstring (length as first byte) from X/Y, returns str len in Y
	%asm {{
		stx  c64.SCRATCH_ZP1
		sty  c64.SCRATCH_ZP2
		ldy  #0
		lda  (c64.SCRATCH_ZP1),y
		beq  +
		tax
-		iny
		lda  (c64.SCRATCH_ZP1),y
		jsr  c64.CHROUT
		dex
		bne  -
+		rts 			; output string length is in Y
	}}
}


sub  print_pimmediate  () -> ()  {
	; ---- print pstring in memory  immediately following the subroutine fast call instruction
	; note that the clobbered registers (A,X,Y) are not listed ON PURPOSE
	%asm {{
		tsx
		lda  $102,x
		tay			; put high byte in y
		lda  $101,x
		tax			; and low byte in x.
		inx
		bne  +
		iny
+		jsr  print_pstring	; print string in XY, returns string length in y.
		tya
		tsx
		clc
		adc  $101,x		; add content of 1st (length) byte to return addr.
		bcc  +      		; if that made the low byte roll over to 00,
		inc  $102,x		; then increment the high byte too.
+		clc
		adc  #1			; now add 1 for the length byte itself.
		sta  $101,x
		bne  +			; if that made it (the low byte) roll over to 00,
		inc  $102,x		; increment the high byte of the return addr too.
+		rts
	}}
}


sub  print_byte_decimal0  (ubyte: A) -> (?)  {
	; ---- print the byte in A in decimal form, with left padding 0s (3 positions total)
	%asm {{
		jsr  byte2decimal
		pha
		tya
		jsr  c64.CHROUT
		txa
		jsr  c64.CHROUT
		pla
		jmp  c64.CHROUT
	}}
}


sub  print_byte_decimal  (ubyte: A) -> (?)  {
	; ---- print the byte in A in decimal form, without left padding 0s
	%asm {{
		jsr  byte2decimal
		pha
		cpy  #'0'
		bne  _print_hundreds
		cpx  #'0'
		bne  _print_tens
		pla
		jmp  c64.CHROUT
_print_hundreds	tya
		jsr  c64.CHROUT
_print_tens	txa
		jsr  c64.CHROUT
		pla
		jmp  c64.CHROUT
	}}
}


sub  print_byte_hex  (prefix: Pc, ubyte: A) -> (?)  {
	; ---- print the byte in A in hex form (if Carry is set, a radix prefix '$' is printed as well)
	%asm {{
		bcc  +
		pha
		lda  #'$'
		jsr  c64.CHROUT
		pla
+		jsr  byte2hex
		txa
		jsr  c64.CHROUT
		tya
		jmp  c64.CHROUT
	}}
}


sub print_word_hex  (prefix: Pc, dataword: XY) -> (?)  {
	; ---- print the (unsigned) word in X/Y in hexadecimal form (4 digits)
	;      (if Carry is set, a radix prefix '$' is printed as well)
	%asm {{
		stx  c64.SCRATCH_ZP1
		tya
		jsr  print_byte_hex
		lda  c64.SCRATCH_ZP1
		clc
		jmp  print_byte_hex
	}}
}


sub  print_word_decimal0  (dataword: XY) -> (?)  {
	; ---- print the (unsigned) word in X/Y in decimal form, with left padding 0s (5 positions total)
	%asm {{
		jsr  word2decimal
		lda  word2decimal_output
		jsr  c64.CHROUT
		lda  word2decimal_output+1
		jsr  c64.CHROUT
		lda  word2decimal_output+2
		jsr  c64.CHROUT
		lda  word2decimal_output+3
		jsr  c64.CHROUT
		lda  word2decimal_output+4
		jmp  c64.CHROUT
	}}
}


sub  print_word_decimal  (dataword: XY) -> (A?, X?, Y?)  {
	; ---- print the word in X/Y in decimal form, without left padding 0s
	%asm {{
		jsr  word2decimal
		ldy  #0
		lda  word2decimal_output
		cmp  #'0'
		bne  _pr_decimal
		iny
		lda  word2decimal_output+1
		cmp  #'0'
		bne  _pr_decimal
		iny
		lda  word2decimal_output+2
		cmp  #'0'
		bne  _pr_decimal
		iny
		lda  word2decimal_output+3
		cmp  #'0'
		bne  _pr_decimal
		iny

_pr_decimal
		lda  word2decimal_output,y
		jsr  c64.CHROUT
		iny
		cpy  #5
		bcc  _pr_decimal
		rts
	}}
}


sub  input_chars  (buffer: AX) -> (A?, Y)  {
	; ---- Input a string (max. 80 chars) from the keyboard.
	;      It assumes the keyboard is selected as I/O channel!!

	%asm {{
		sta  c64.SCRATCH_ZP1
		stx  c64.SCRATCH_ZP2
		ldy  #0				; char counter = 0
-		jsr  c64.CHRIN
		cmp  #$0d			; return (ascii 13) pressed?
		beq  +				; yes, end.
		sta  (c64.SCRATCH_ZP1),y	; else store char in buffer
		iny
		bne  -
+		lda  #0
		sta  (c64.SCRATCH_ZP1),y	; finish string with 0 byte
		rts

	}}
}

}  ; ---- end block c64scr



;sub  memcopy_basic  () -> (?)  {
;	; ---- copy a memory block by using a BASIC ROM routine
;	; it calls a function from the basic interpreter, so:
;	;       - BASIC ROM must be banked in
;	;       - the source block must be readable (so no RAM hidden under BASIC, Kernal, or I/O)
;	;       - the target block must be writable (so no RAM hidden under I/O)
;	; higher addresses are copied first, so:
;	;       - moving data to higher addresses works even if areas overlap
;	;       - moving data to lower addresses only works if areas do not overlap
;	%asm {{
;		lda  #<src_start
;		ldx  #>src_start
;		sta  $5f
;		stx  $60
;		lda  #<src_end
;		ldx  #>src_end
;		sta  $5a
;		stx  $5b
;		lda  #<(target_start + src_end - src_start)
;		ldx  #>(target_start + src_end - src_start)
;		sta  $58
;		stx  $59
;		jmp  $a3bf
;	}
;}

; macro version of the above memcopy_basic routine:
; MACRO PARAMS src_start, src_end, target_start
;		lda  #<src_start
;		ldx  #>src_start
;		sta  $5f
;		stx  $60
;		lda  #<src_end
;		ldx  #>src_end
;		sta  $5a
;		stx  $5b
;		lda  #<(target_start + src_end - src_start)
;		ldx  #>(target_start + src_end - src_start)
;		sta  $58
;		stx  $59
;		jsr  $a3bf
