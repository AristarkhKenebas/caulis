/*******************************************************************************
* Lua's pattern matching (string.find, match, gmatch and gsub), taken from LuaJ 3.0.1
* (org.luaj.vm2.lib.StringLib) with one change: the matcher counts its steps and asks
* [check] every so often whether the Lua code calling it has run too long. LuaJ's own
* matcher runs in Java, where the time limit's debug hook never comes, so a pattern that
* backtracks without end ('(.-)(.-)(.-)...b' on a long string) froze the desktop.
*
* Copyright (c) 2009-2011 Luaj.org. All rights reserved.
*
* Permission is hereby granted, free of charge, to any person obtaining a copy
* of this software and associated documentation files (the "Software"), to deal
* in the Software without restriction, including without limitation the rights
* to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
* copies of the Software, and to permit persons to whom the Software is
* furnished to do so, subject to the following conditions:
*
* The above copyright notice and this permission notice shall be included in
* all copies or substantial portions of the Software.
*
* THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
* IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
* FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
* AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
* LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
* OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
* THE SOFTWARE.
******************************************************************************/
package dev.caulis.lua;

import org.luaj.vm2.Buffer;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.lib.VarArgFunction;

import static org.luaj.vm2.LuaValue.*;

// string.find, match, gmatch and gsub whose matching asks [check] every STEPS steps (it
// throws when the time is up).
final class LuaPatterns {

	// How many matcher steps between two calls of check.
	static final int STEPS = 4096;

	private final Runnable check;

	LuaPatterns(Runnable check) {
		this.check = check;
	}

	// Puts find, match, gmatch and gsub into [string] (the string table of a Lua state).
	void install(LuaTable string) {
		string.set("find", new VarArgFunction() {
			public Varargs invoke(Varargs args) {
				return str_find_aux(args, true);
			}
		});
		string.set("match", new VarArgFunction() {
			public Varargs invoke(Varargs args) {
				return str_find_aux(args, false);
			}
		});
		string.set("gmatch", new VarArgFunction() {
			public Varargs invoke(Varargs args) {
				LuaString src = args.checkstring( 1 );
				LuaString pat = args.checkstring( 2 );
				return new GMatchAux(args, src, pat);
			}
		});
		string.set("gsub", new VarArgFunction() {
			public Varargs invoke(Varargs args) {
				return gsub(args);
			}
		});
	}
	class GMatchAux extends VarArgFunction {
		private final int srclen;
		private final MatchState ms;
		private int soffset;
		public GMatchAux(Varargs args, LuaString src, LuaString pat) {
			this.srclen = src.length();
			this.ms = new MatchState(args, src, pat);
			this.soffset = 0;
		}
		public Varargs invoke(Varargs args) {
			for ( ; soffset<srclen; soffset++ ) {
				ms.reset();
				int res = ms.match(soffset, 0);
				if ( res >=0 ) {
					int soff = soffset;
					soffset = res;
					return ms.push_captures( true, soff, res );
				}
			}
			return NIL;
		}
	}

	Varargs gsub(Varargs args) {
		LuaString src = args.checkstring( 1 );
		final int srclen = src.length();
		LuaString p = args.checkstring( 2 );
		LuaValue repl = args.arg( 3 );
		int max_s = args.optint( 4, srclen + 1 );
		final boolean anchor = p.length() > 0 && p.charAt( 0 ) == '^';
		
		Buffer lbuf = new Buffer( srclen );
		MatchState ms = new MatchState( args, src, p );
		
		int soffset = 0;
		int n = 0;
		while ( n < max_s ) {
			ms.reset();
			int res = ms.match( soffset, anchor ? 1 : 0 );
			if ( res != -1 ) {
				n++;
				ms.add_value( lbuf, soffset, res, repl );
			}
			if ( res != -1 && res > soffset )
				soffset = res;
			else if ( soffset < srclen )
				lbuf.append( (byte) src.luaByte( soffset++ ) );
			else
				break;
			if ( anchor )
				break;
		}
		lbuf.append( src.substring( soffset, srclen ) );
		return varargsOf(lbuf.tostring(), valueOf(n));
	}

	Varargs str_find_aux( Varargs args, boolean find ) {
		LuaString s = args.checkstring( 1 );
		LuaString pat = args.checkstring( 2 );
		int init = args.optint( 3, 1 );
		
		if ( init > 0 ) {
			init = Math.min( init - 1, s.length() );
		} else if ( init < 0 ) {
			init = Math.max( 0, s.length() + init );
		}
		
		boolean fastMatch = find && ( args.arg(4).toboolean() || pat.indexOfAny( SPECIALS ) == -1 );
		
		if ( fastMatch ) {
			int result = s.indexOf( pat, init );
			if ( result != -1 ) {
				return varargsOf( valueOf(result+1), valueOf(result+pat.length()) );
			}
		} else {
			MatchState ms = new MatchState( args, s, pat );
			
			boolean anchor = false;
			int poff = 0;
			if ( pat.luaByte( 0 ) == '^' ) {
				anchor = true;
				poff = 1;
			}
			
			int soff = init;
			do {
				int res;
				ms.reset();
				if ( ( res = ms.match( soff, poff ) ) != -1 ) {
					if ( find ) {
						return varargsOf( valueOf(soff+1), valueOf(res), ms.push_captures( false, soff, res ));
					} else {
						return ms.push_captures( true, soff, res );
					}
				}
			} while ( soff++ < s.length() && !anchor );
		}
		return NIL;
	}
	
	static int posrelat( int pos, int len ) {
		return ( pos >= 0 ) ? pos : len + pos + 1;
	}
	
	// Pattern matching implementation
	
	private static final int L_ESC = '%';
	private static final LuaString SPECIALS = valueOf("^$*+?.([%-");
	private static final int MAX_CAPTURES = 32;
	
	private static final int CAP_UNFINISHED = -1;
	private static final int CAP_POSITION = -2;
	
	private static final byte MASK_ALPHA		= 0x01;
	private static final byte MASK_LOWERCASE	= 0x02;
	private static final byte MASK_UPPERCASE	= 0x04;
	private static final byte MASK_DIGIT		= 0x08;
	private static final byte MASK_PUNCT		= 0x10;
	private static final byte MASK_SPACE		= 0x20;
	private static final byte MASK_CONTROL		= 0x40;
	private static final byte MASK_HEXDIGIT		= (byte)0x80;
	
	private static final byte[] CHAR_TABLE;
	
	static {
		CHAR_TABLE = new byte[256];
		
		for ( int i = 0; i < 256; ++i ) {
			final char c = (char) i;
			CHAR_TABLE[i] = (byte)( ( Character.isDigit( c ) ? MASK_DIGIT : 0 ) |
							( Character.isLowerCase( c ) ? MASK_LOWERCASE : 0 ) |
							( Character.isUpperCase( c ) ? MASK_UPPERCASE : 0 ) |
							( ( c < ' ' || c == 0x7F ) ? MASK_CONTROL : 0 ) );
			if ( ( c >= 'a' && c <= 'f' ) || ( c >= 'A' && c <= 'F' ) || ( c >= '0' && c <= '9' ) ) {
				CHAR_TABLE[i] |= MASK_HEXDIGIT;
			}
			if ( ( c >= '!' && c <= '/' ) || ( c >= ':' && c <= '@' ) ) {
				CHAR_TABLE[i] |= MASK_PUNCT;
			}
			if ( ( CHAR_TABLE[i] & ( MASK_LOWERCASE | MASK_UPPERCASE ) ) != 0 ) {
				CHAR_TABLE[i] |= MASK_ALPHA;
			}
		}
		
		CHAR_TABLE[' '] = MASK_SPACE;
		CHAR_TABLE['\r'] |= MASK_SPACE;
		CHAR_TABLE['\n'] |= MASK_SPACE;
		CHAR_TABLE['\t'] |= MASK_SPACE;
		CHAR_TABLE[0x0C /* '\v' */ ] |= MASK_SPACE;
		CHAR_TABLE['\f'] |= MASK_SPACE;
	};
	
	class MatchState {
		// Steps since the last check.
		int steps;

		// One more step: every STEPS of them, has the time run out?
		void step() {
			if ( ++steps >= STEPS ) {
				steps = 0;
				check.run();
			}
		}
		final LuaString s;
		final LuaString p;
		final Varargs args;
		int level;
		int[] cinit;
		int[] clen;
		
		MatchState( Varargs args, LuaString s, LuaString pattern ) {
			this.s = s;
			this.p = pattern;
			this.args = args;
			this.level = 0;
			this.cinit = new int[ MAX_CAPTURES ];
			this.clen = new int[ MAX_CAPTURES ];
		}
		
		void reset() {
			level = 0;
		}
		
		private void add_s( Buffer lbuf, LuaString news, int soff, int e ) {
			int l = news.length();
			for ( int i = 0; i < l; ++i ) {
				byte b = (byte) news.luaByte( i );
				if ( b != L_ESC ) {
					lbuf.append( (byte) b );
				} else {
					++i; // skip ESC
					b = (byte) news.luaByte( i );
					if ( !Character.isDigit( (char) b ) ) {
						lbuf.append( b );
					} else if ( b == '0' ) {
						lbuf.append( s.substring( soff, e ) );
					} else {
						lbuf.append( push_onecapture( b - '1', soff, e ).strvalue() );
					}
				}
			}
		}
		
		public void add_value( Buffer lbuf, int soffset, int end, LuaValue repl ) {
			switch ( repl.type() ) {
			case LuaValue.TSTRING:
			case LuaValue.TNUMBER:
				add_s( lbuf, repl.strvalue(), soffset, end );
				return;
				
			case LuaValue.TFUNCTION:
				repl = repl.invoke( push_captures( true, soffset, end ) ).arg1();
				break;
				
			case LuaValue.TTABLE:
				// Need to call push_onecapture here for the error checking
				repl = repl.get( push_onecapture( 0, soffset, end ) );
				break;
				
			default:
				error( "bad argument: string/function/table expected" );
				return;
			}
			
			if ( !repl.toboolean() ) {
				repl = s.substring( soffset, end );
			} else if ( ! repl.isstring() ) {
				error( "invalid replacement value (a "+repl.typename()+")" );
			}
			lbuf.append( repl.strvalue() );
		}
		
		Varargs push_captures( boolean wholeMatch, int soff, int end ) {
			int nlevels = ( this.level == 0 && wholeMatch ) ? 1 : this.level;
			switch ( nlevels ) {
			case 0: return NONE;
			case 1: return push_onecapture( 0, soff, end );
			}
			LuaValue[] v = new LuaValue[nlevels];
			for ( int i = 0; i < nlevels; ++i )
				v[i] = push_onecapture( i, soff, end );
			return varargsOf(v);
		}
		
		private LuaValue push_onecapture( int i, int soff, int end ) {
			if ( i >= this.level ) {
				if ( i == 0 ) {
					return s.substring( soff, end );
				} else {
					return error( "invalid capture index" );
				}
			} else {
				int l = clen[i];
				if ( l == CAP_UNFINISHED ) {
					return error( "unfinished capture" );
				}
				if ( l == CAP_POSITION ) {
					return valueOf( cinit[i] + 1 );
				} else {
					int begin = cinit[i];
					return s.substring( begin, begin + l );
				}
			}
		}
		
		private int check_capture( int l ) {
			l -= '1';
			if ( l < 0 || l >= level || this.clen[l] == CAP_UNFINISHED ) {
				error("invalid capture index");
			}
			return l;
		}
		
		private int capture_to_close() {
			int level = this.level;
			for ( level--; level >= 0; level-- )
				if ( clen[level] == CAP_UNFINISHED )
					return level;
			error("invalid pattern capture");
			return 0;
		}
		
		int classend( int poffset ) {
			switch ( p.luaByte( poffset++ ) ) {
			case L_ESC:
				if ( poffset == p.length() ) {
					error( "malformed pattern (ends with %)" );
				}
				return poffset + 1;
				
			case '[':
				if ( p.luaByte( poffset ) == '^' ) poffset++;
				do {
					if ( poffset == p.length() ) {
						error( "malformed pattern (missing ])" );
					}
					if ( p.luaByte( poffset++ ) == L_ESC && poffset != p.length() )
						poffset++;
				} while ( p.luaByte( poffset ) != ']' );
				return poffset + 1;
			default:
				return poffset;
			}
		}
		
		boolean match_class( int c, int cl ) {
			final char lcl = Character.toLowerCase( (char) cl );
			int cdata = CHAR_TABLE[c];
			
			boolean res;
			switch ( lcl ) {
			case 'a': res = ( cdata & MASK_ALPHA ) != 0; break;
			case 'd': res = ( cdata & MASK_DIGIT ) != 0; break;
			case 'l': res = ( cdata & MASK_LOWERCASE ) != 0; break;
			case 'u': res = ( cdata & MASK_UPPERCASE ) != 0; break;
			case 'c': res = ( cdata & MASK_CONTROL ) != 0; break;
			case 'p': res = ( cdata & MASK_PUNCT ) != 0; break;
			case 's': res = ( cdata & MASK_SPACE ) != 0; break;
			case 'w': res = ( cdata & ( MASK_ALPHA | MASK_DIGIT ) ) != 0; break;
			case 'x': res = ( cdata & MASK_HEXDIGIT ) != 0; break;
			case 'z': res = ( c == 0 ); break;
			default: return cl == c;
			}
			return ( lcl == cl ) ? res : !res;
		}
		
		boolean matchbracketclass( int c, int poff, int ec ) {
			boolean sig = true;
			if ( p.luaByte( poff + 1 ) == '^' ) {
				sig = false;
				poff++;
			}
			while ( ++poff < ec ) {
				if ( p.luaByte( poff ) == L_ESC ) {
					poff++;
					if ( match_class( c, p.luaByte( poff ) ) )
						return sig;
				}
				else if ( ( p.luaByte( poff + 1 ) == '-' ) && ( poff + 2 < ec ) ) {
					poff += 2;
					if ( p.luaByte( poff - 2 ) <= c && c <= p.luaByte( poff ) )
						return sig;
				}
				else if ( p.luaByte( poff ) == c ) return sig;
			}
			return !sig;
		}
		
		boolean singlematch( int c, int poff, int ep ) {
			switch ( p.luaByte( poff ) ) {
			case '.': return true;
			case L_ESC: return match_class( c, p.luaByte( poff + 1 ) );
			case '[': return matchbracketclass( c, poff, ep - 1 );
			default: return p.luaByte( poff ) == c;
			}
		}
		
		/**
		 * Perform pattern matching. If there is a match, returns offset into s
		 * where match ends, otherwise returns -1.
		 */
		int match( int soffset, int poffset ) {
			while ( true ) {
				step();
				// Check if we are at the end of the pattern - 
				// equivalent to the '\0' case in the C version, but our pattern
				// string is not NUL-terminated.
				if ( poffset == p.length() )
					return soffset;
				switch ( p.luaByte( poffset ) ) {
				case '(':
					if ( ++poffset < p.length() && p.luaByte( poffset ) == ')' )
						return start_capture( soffset, poffset + 1, CAP_POSITION );
					else
						return start_capture( soffset, poffset, CAP_UNFINISHED );
				case ')':
					return end_capture( soffset, poffset + 1 );
				case L_ESC:
					if ( poffset + 1 == p.length() )
						error("malformed pattern (ends with '%')");
					switch ( p.luaByte( poffset + 1 ) ) {
					case 'b':
						soffset = matchbalance( soffset, poffset + 2 );
						if ( soffset == -1 ) return -1;
						poffset += 4;
						continue;
					case 'f': {
						poffset += 2;
						if ( p.luaByte( poffset ) != '[' ) {
							error("Missing [ after %f in pattern");
						}
						int ep = classend( poffset );
						// (As Lua has it — LuaJ's own failed whenever the next character was in
						// the set: a frontier is where the one before is not, and the next is.
						// Before the start and after the end there is a \0.)
						int previous = ( soffset == 0 ) ? 0 : s.luaByte( soffset - 1 );
						int next = ( soffset < s.length() ) ? s.luaByte( soffset ) : 0;
						if ( matchbracketclass( previous, poffset, ep - 1 ) ||
							 !matchbracketclass( next, poffset, ep - 1 ) )
							return -1;
						poffset = ep;
						continue;
					}
					default: {
						int c = p.luaByte( poffset + 1 );
						if ( Character.isDigit( (char) c ) ) {
							soffset = match_capture( soffset, c );
							if ( soffset == -1 )
								return -1;
							return match( soffset, poffset + 2 );
						}
					}
					}
				case '$':
					if ( poffset + 1 == p.length() )
						return ( soffset == s.length() ) ? soffset : -1;
				}
				int ep = classend( poffset );
				boolean m = soffset < s.length() && singlematch( s.luaByte( soffset ), poffset, ep );
				int pc = ( ep < p.length() ) ? p.luaByte( ep ) : '\0';
				
				switch ( pc ) {
				case '?':
					int res;
					if ( m && ( ( res = match( soffset + 1, ep + 1 ) ) != -1 ) )
						return res;
					poffset = ep + 1;
					continue;
				case '*':
					return max_expand( soffset, poffset, ep );
				case '+':
					return ( m ? max_expand( soffset + 1, poffset, ep ) : -1 );
				case '-':
					return min_expand( soffset, poffset, ep );
				default:
					if ( !m )
						return -1;
					soffset++;
					poffset = ep;
					continue;
				}
			}
		}
		
		int max_expand( int soff, int poff, int ep ) {
			int i = 0;
			while ( soff + i < s.length() &&
					singlematch( s.luaByte( soff + i ), poff, ep ) )
				i++;
			while ( i >= 0 ) {
				step();
				int res = match( soff + i, ep + 1 );
				if ( res != -1 )
					return res;
				i--;
			}
			return -1;
		}
		
		int min_expand( int soff, int poff, int ep ) {
			for ( ;; ) {
				step();
				int res = match( soff, ep + 1 );
				if ( res != -1 )
					return res;
				else if ( soff < s.length() && singlematch( s.luaByte( soff ), poff, ep ) )
					soff++;
				else return -1;
			}
		}
		
		int start_capture( int soff, int poff, int what ) {
			int res;
			int level = this.level;
			if ( level >= MAX_CAPTURES ) {
				error( "too many captures" );
			}
			cinit[ level ] = soff;
			clen[ level ] = what;
			this.level = level + 1;
			if ( ( res = match( soff, poff ) ) == -1 )
				this.level--;
			return res;
		}
		
		int end_capture( int soff, int poff ) {
			int l = capture_to_close();
			int res;
			clen[l] = soff - cinit[l];
			if ( ( res = match( soff, poff ) ) == -1 )
				clen[l] = CAP_UNFINISHED;
			return res;
		}
		
		int match_capture( int soff, int l ) {
			l = check_capture( l );
			int len = clen[ l ];
			if ( ( s.length() - soff ) >= len &&
				 LuaString.equals( s, cinit[l], s, soff, len ) )
				return soff + len;
			else
				return -1;
		}
		
		int matchbalance( int soff, int poff ) {
			final int plen = p.length();
			if ( poff == plen || poff + 1 == plen ) {
				error( "unbalanced pattern" );
			}
			final int slen = s.length();
			if ( soff >= slen )
				return -1;
			final int b = p.luaByte( poff );
			if ( s.luaByte( soff ) != b )
				return -1;
			final int e = p.luaByte( poff + 1 );
			int cont = 1;
			while ( ++soff < slen ) {
				if ( s.luaByte( soff ) == e ) {
					if ( --cont == 0 ) return soff + 1;
				}
				else if ( s.luaByte( soff ) == b ) cont++;
			}
			return -1;
		}
	}
}
