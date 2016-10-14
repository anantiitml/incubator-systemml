/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */


package org.apache.sysml.runtime.matrix.data;

import java.util.Arrays;

import org.apache.sysml.runtime.util.SortUtils;

/**
 * SparseBlock implementation that realizes a traditional 'compressed sparse row'
 * representation, where the entire sparse block is stored as three arrays: ptr
 * of length rlen+1 to store offsets per row, and indexes/values of length nnz
 * to store column indexes and values of non-zero entries. This format is very
 * memory efficient for sparse (but not ultra-sparse) matrices and provides very 
 * good performance for common operations, partially due to lower memory bandwidth 
 * requirements. However, this format is slow on incremental construction (because 
 * it does not allow append/sort per row) without reshifting. Finally, the total 
 * nnz is limited to INTEGER_MAX, whereas for SparseBlockMCSR only the nnz per 
 * row are limited to INTEGER_MAX.  
 * 
 * TODO: extensions for faster incremental construction (e.g., max row)
 * TODO more efficient fused setIndexRange impl to avoid repeated copies and updates
 * 	
 */
public class SparseBlockCSR extends SparseBlock 
{
	private static final long serialVersionUID = 1922673868466164244L;

	private int[] _ptr = null;       //row pointer array (size: rlen+1)
	private int[] _indexes = null;   //column index array (size: >=nnz)
	private double[] _values = null; //value array (size: >=nnz)
	private int _size = 0;           //actual number of nnz
	
	public SparseBlockCSR(int rlen) {
		this(rlen, INIT_CAPACITY);
	}
	
	public SparseBlockCSR(int rlen, int capacity) {
		_ptr = new int[rlen+1]; //ix0=0
		_indexes = new int[capacity];
		_values = new double[capacity];
		_size = 0;
	}
	
	public SparseBlockCSR(int[] rowPtr, int[] colInd, double[] values, int nnz){
		_ptr = rowPtr;
		_indexes = colInd;
		_values = values;
		_size = nnz;
	}
	
	/**
	 * Copy constructor sparse block abstraction. 
	 */
	public SparseBlockCSR(SparseBlock sblock)
	{
		long size = sblock.size();
		if( size > Integer.MAX_VALUE )
			throw new RuntimeException("SparseBlockCSR supports nnz<=Integer.MAX_VALUE but got "+size);
		
		//special case SparseBlockCSR
		if( sblock instanceof SparseBlockCSR ) { 
			SparseBlockCSR ocsr = (SparseBlockCSR)sblock;
			_ptr = Arrays.copyOf(ocsr._ptr, ocsr.numRows()+1);
			_indexes = Arrays.copyOf(ocsr._indexes, ocsr._size);
			_values = Arrays.copyOf(ocsr._values, ocsr._size);
			_size = ocsr._size;
		}
		//general case SparseBlock
		else {
			int rlen = sblock.numRows();
			
			_ptr = new int[rlen+1];
			_indexes = new int[(int)size];
			_values = new double[(int)size];
			_size = (int)size;

			for( int i=0, pos=0; i<rlen; i++ ) {
				if( !sblock.isEmpty(i) ) {
					int apos = sblock.pos(i);
					int alen = sblock.size(i);
					int[] aix = sblock.indexes(i);
					double[] avals = sblock.values(i);
					System.arraycopy(aix, apos, _indexes, pos, alen);
					System.arraycopy(avals, apos, _values, pos, alen);
					pos += alen;
				}
				_ptr[i+1]=pos;
			}			
		}
	}
	
	/**
	 * Copy constructor old sparse row representation. 
	 * @param rows
	 * @param nnz number of non-zeroes
	 */
	public SparseBlockCSR(SparseRow[] rows, int nnz)
	{
		int rlen = rows.length;
		
		_ptr = new int[rlen+1]; //ix0=0
		_indexes = new int[nnz];
		_values = new double[nnz];
		_size = nnz;
		
		for( int i=0, pos=0; i<rlen; i++ ) {
			if( rows[i]!=null && !rows[i].isEmpty() ) {
				int alen = rows[i].size();
				int[] aix = rows[i].indexes();
				double[] avals = rows[i].values();
				System.arraycopy(aix, 0, _indexes, pos, alen);
				System.arraycopy(avals, 0, _values, pos, alen);
				pos += alen;
			}
			_ptr[i+1]=pos;	
		}
	}
	
	/**
	 * Copy constructor for COO representation
	 * @param rowInd	row indices
	 * @param colInd	column indices
	 * @param values	non zero values
	 */
	public SparseBlockCSR(int rows, int[] rowInd, int[] colInd, double[] values){
		int nnz = values.length;
		_ptr = new int[rows+1];
		_indexes = Arrays.copyOf(colInd, colInd.length);
		_values = Arrays.copyOf(values, values.length);
		_size = nnz;
		
		for (int i=0; i<rows; i++){
			_ptr[i] = -1;
		}
		_ptr[rows] = nnz;
		_ptr[0]    = 0;
		
		// Input Example -> rowInd = [0,0,1,1,2,2,2,4,4,5]
		//							 [0,1,2,3,4,5,6,7,8,9]
		for (int i=nnz-1; i>=1; i--){
			_ptr[rowInd[i]] = i;
		}
		// Output Example -> _ptr = [0|2|_|4|7|9|nnz]
		// _ = -1
		
		// Pad out the missing values
		// Input example -> _ptr = [0|2|_|4|7|9|nnz]
		for (int i=1; i<rows; i++){
			if (_ptr[i] == -1){
				_ptr[i] = _ptr[i-1];
			}
		}
		// Output example -> _ptr = [0|2|2|4|7|9|nnz]
				
	}
	
	/**
	 * Get the estimated in-memory size of the sparse block in CSR 
	 * with the given dimensions w/o accounting for overallocation. 
	 * 
	 * @param nrows
	 * @param ncols
	 * @param sparsity
	 * @return
	 */
	public static long estimateMemory(long nrows, long ncols, double sparsity) {
		double lnnz = Math.max(INIT_CAPACITY, Math.ceil(sparsity*nrows*ncols));
		
		//32B overhead per array, int arr in nrows, int/double arr in nnz 
		double size = 16 + 4;        //object + int field
		size += 32 + (nrows+1) * 4d; //ptr array (row pointers)
		size += 32 + lnnz * 4d;      //indexes array (column indexes)
		size += 32 + lnnz * 8d;      //values array (non-zero values)
		
		//robustness for long overflows
		return (long) Math.min(size, Long.MAX_VALUE);
	}
	
	///////////////////
	//SparseBlock implementation

	@Override
	public void allocate(int r) {
		//do nothing everything preallocated
	}
	
	@Override
	public void allocate(int r, int nnz) {
		//do nothing everything preallocated
	}
	
	@Override
	public void allocate(int r, int ennz, int maxnnz) {
		//do nothing everything preallocated
	}

	@Override
	public int numRows() {
		return _ptr.length-1;
	}

	@Override
	public boolean isThreadSafe() {
		return false;
	}
	
	@Override
	public boolean isContiguous() {
		return true;
	}
	
	@Override 
	public void reset() {
		_size = 0;
		Arrays.fill(_ptr, 0);
	}

	@Override 
	public void reset(int ennz, int maxnnz) {
		_size = 0;
		Arrays.fill(_ptr, 0);
	}
	
	@Override 
	public void reset(int r, int ennz, int maxnnz) {
		int pos = pos(r);
		int len = size(r);
		
		if( len > 0 ) {
			//overlapping array copy (shift rhs values left)
			System.arraycopy(_indexes, pos+len, _indexes, pos, _size-(pos+len));
			System.arraycopy(_values, pos+len, _values, pos, _size-(pos+len));
			_size -= len;	
			decrPtr(r+1, len);
		}
	}
	
	@Override
	public long size() {
		return _size;
	}

	@Override
	public int size(int r) {
		return _ptr[r+1] - _ptr[r];
	}
	
	@Override
	public long size(int rl, int ru) {
		return _ptr[ru] - _ptr[rl];
	}

	@Override
	public long size(int rl, int ru, int cl, int cu) {
		long nnz = 0;
		for(int i=rl; i<ru; i++)
			if( !isEmpty(i) ) {
				int start = posFIndexGTE(i, cl);
				int end = posFIndexGTE(i, cu);
				nnz += (start!=-1) ? (end-start) : 0;
			}
		return nnz;
	}
	
	@Override
	public boolean isEmpty(int r) {
		return (_ptr[r+1] - _ptr[r] == 0);
	}
	
	@Override
	public int[] indexes(int r) {
		return _indexes;
	}

	@Override
	public double[] values(int r) {
		return _values;
	}

	@Override
	public int pos(int r) {
		return _ptr[r];
	}

	@Override
	public boolean set(int r, int c, double v) {
		int pos = pos(r);
		int len = size(r);
		
		//search for existing col index
		int index = Arrays.binarySearch(_indexes, pos, pos+len, c);
		if( index >= 0 ) {
			//delete/overwrite existing value (on value delete, we shift 
			//left for (1) correct nnz maintenance, and (2) smaller size)
			if( v == 0 ) {
				shiftLeftAndDelete(index);
				decrPtr(r+1);
				return true; // nnz--
			}
			else { 	
				_values[index] = v;
				return false;
			} 
		}

		//early abort on zero (if no overwrite)
		if( v==0 ) return false;
		
		//insert new index-value pair
		index = Math.abs( index+1 );
		if( _size==_values.length )
			resizeAndInsert(index, c, v);
		else
			shiftRightAndInsert(index, c, v);
		incrPtr(r+1);
		return true; // nnz++
	}

	@Override
	public void set(int r, SparseRow row, boolean deep) {
		int pos = pos(r);
		int len = size(r);		
		int alen = row.size();
		int[] aix = row.indexes();
		double[] avals = row.values();
		
		//delete existing values if necessary
		if( len > 0 )
			deleteIndexRange(r, aix[0], aix[alen-1]+1);
		
		//prepare free space (allocate and shift)
		int lsize = _size+alen;
		if( _values.length < lsize )
			resize(lsize);				
		shiftRightByN(pos, alen);
		
		//copy input row into internal representation
		System.arraycopy(aix, 0, _indexes, pos, alen);
		System.arraycopy(avals, 0, _values, pos, alen);
		_size+=alen;
	}
	
	@Override
	public void append(int r, int c, double v) {
		//early abort on zero 
		if( v==0 ) return;
	
		int pos = pos(r);
		int len = size(r);
		if( pos+len == _size ) {
			//resize and append
			if( _size==_values.length )
				resize();
			insert(_size, c, v);		
		}		
		else {
			//resize, shift and insert
			if( _size==_values.length )
				resizeAndInsert(pos+len, c, v);
			else
				shiftRightAndInsert(pos+len, c, v);
		}			
		incrPtr(r+1);
	}

	@Override
	public void setIndexRange(int r, int cl, int cu, double[] v, int vix, int vlen) {
		//delete existing values in range if necessary 
		if( !isEmpty(r) )
			deleteIndexRange(r, cl, cu);
		
		//determine input nnz
		int lnnz = 0;
		for( int i=vix; i<vix+vlen; i++ )
			lnnz += ( v[i] != 0 ) ? 1 : 0;

		//prepare free space (allocate and shift)
		int lsize = _size+lnnz;
		if( _values.length < lsize )
			resize(lsize);
		int index = posFIndexGT(r, cl);
		int index2 = (index>0)?index:pos(r+1);
		shiftRightByN(index2, lnnz);
		
		//insert values
		for( int i=vix; i<vix+vlen; i++ )
			if( v[i] != 0 ) {
				_indexes[ index2 ] = cl+i-vix;
				_values[ index2 ] = v[i];
				index2++;
			}
		incrPtr(r+1, lnnz);
	}
	
	/**
	 * Inserts a sorted row-major array of non-zero values into the row and column 
	 * range [rl,ru) and [cl,cu). Note: that this is a CSR-specific method to address 
	 * performance issues due to repeated re-shifting on update-in-place.
	 * 
	 * @param rl  lower row index, starting at 0, inclusive
	 * @param ru  upper row index, starting at 0, exclusive
	 * @param cl  lower column index, starting at 0, inclusive
	 * @param cu  upper column index, starting at 0, exclusive
	 * @param v   right-hand-side dense block
	 * @param vix right-hand-side dense block index
	 * @param vlen right-hand-side dense block value length 
	 */
	public void setIndexRange(int rl, int ru, int cl, int cu, double[] v, int vix, int vlen) {
		//step 1: determine output nnz
		int nnz = _size - (int)size(rl, ru, cl, cu);
		if( v != null )
			for( int i=vix; i<vix+vlen; i++ )
				nnz += (v[i]!=0) ? 1: 0;
		
		//step 2: reallocate if necessary
		if( _values.length < nnz )
			resize(nnz);
		
		//step 3: insert and overwrite index range
		//total shift can be negative or positive and w/ internal skew
		
		//step 3a: forward pass: compact (delete index range)
		int pos = pos(rl);
		for( int r=rl; r<ru; r++ ) {
			int rpos = pos(r);
			int rlen = size(r);
			_ptr[r] = pos;
			for( int k=rpos; k<rpos+rlen; k++ )
				if( _indexes[k]<cl || cu<=_indexes[k] ) {
					_indexes[pos] = _indexes[k];
					_values[pos++] = _values[k];
				}
		}
		shiftLeftByN(pos(ru), pos(ru)-pos);
		decrPtr(ru, pos(ru)-pos);
		
		//step 3b: backward pass: merge (insert index range)
		int tshift1 = nnz - _size; //always non-negative
		if( v == null || tshift1==0 ) //early abort
			return;
		shiftRightByN(pos(ru), tshift1);
		incrPtr(ru, tshift1);
		pos = pos(ru)-1;
		int clen2 = cu-cl;
		for( int r=ru-1; r>=rl; r-- ) {
			int rpos = pos(r);
			int rlen = size(r) - tshift1;
			//copy lhs right
			int k = -1;
			for( k=rpos+rlen-1; k>=rpos && _indexes[k]>=cu; k-- ) {
				_indexes[pos] = _indexes[k];
				_values[pos--] = _values[k];
			}
			//copy rhs
			int voff = vix + (r-rl) * clen2; 
			for( int k2=clen2-1; k2>=0 & vlen>voff; k2-- ) 
				if( v[voff+k2] != 0 ) {
					_indexes[pos] = cl + k2;
					_values[pos--] = v[voff+k2];
					tshift1--;
				}
			//copy lhs left
			for( ; k>=rpos; k-- ) {
				_indexes[pos] = _indexes[k];
				_values[pos--] = _values[k];
			}
			_ptr[r] = pos+1; 
		}
	}
	
	/**
	 * Inserts a sparse block into the row and column range [rl,ru) and [cl,cu). 
	 * Note: that this is a CSR-specific method to address  performance issues 
	 * due to repeated re-shifting on update-in-place.
	 * 
	 * @param rl  lower row index, starting at 0, inclusive
	 * @param ru  upper row index, starting at 0, exclusive
	 * @param cl  lower column index, starting at 0, inclusive
	 * @param cu  upper column index, starting at 0, exclusive
	 * @param sb  right-hand-side sparse block
	 */
	public void setIndexRange(int rl, int ru, int cl, int cu, SparseBlock sb) {
		//step 1: determine output nnz
		int nnz = (int) (_size - size(rl, ru, cl, cu) 
				+ ((sb!=null) ? sb.size() : 0));
		
		//step 2: reallocate if necessary
		if( _values.length < nnz )
			resize(nnz);
		
		//step 3: insert and overwrite index range (backwards)
		//total shift can be negative or positive and w/ internal skew
		
		//step 3a: forward pass: compact (delete index range)
		int pos = pos(rl);
		for( int r=rl; r<ru; r++ ) {
			int rpos = pos(r);
			int rlen = size(r);
			_ptr[r] = pos;
			for( int k=rpos; k<rpos+rlen; k++ )
				if( _indexes[k]<cl || cu<=_indexes[k] ) {
					_indexes[pos] = _indexes[k];
					_values[pos++] = _values[k];
				}
		}
		shiftLeftByN(pos(ru), pos(ru)-pos);
		decrPtr(ru, pos(ru)-pos);
		
		//step 3b: backward pass: merge (insert index range)
		int tshift1 = nnz - _size; //always non-negative
		if( sb == null || tshift1==0 ) //early abort
			return;
		shiftRightByN(pos(ru), tshift1);
		incrPtr(ru, tshift1);
		pos = pos(ru)-1;
		for( int r=ru-1; r>=rl; r-- ) {
			int rpos = pos(r);
			int rlen = size(r) - tshift1;
			//copy lhs right
			int k = -1;
			for( k=rpos+rlen-1; k>=rpos && _indexes[k]>=cu; k-- ) {
				_indexes[pos] = _indexes[k];
				_values[pos--] = _values[k];
			}
			//copy rhs
			int r2 = r-rl; 
			int r2pos = sb.pos(r2);
			for( int k2=r2pos+sb.size(r2)-1; k2>=r2pos; k2-- ) {
				_indexes[pos] = cl + sb.indexes(r2)[k2];
				_values[pos--] = sb.values(r2)[k2];
				tshift1--;
			}
			//copy lhs left
			for( ; k>=rpos; k-- ) {
				_indexes[pos] = _indexes[k];
				_values[pos--] = _values[k];
			}
			_ptr[r] = pos+1; 
		}
	} 

	@Override
	public void deleteIndexRange(int r, int cl, int cu) {
		int start = posFIndexGTE(r,cl);
		if( start < 0 ) //nothing to delete 
			return;		

		int len = size(r);
		int end = posFIndexGTE(r, cu);
		if( end < 0 ) //delete all remaining
			end = start+len;
		
		//overlapping array copy (shift rhs values left)
		System.arraycopy(_indexes, end, _indexes, start, _size-end);
		System.arraycopy(_values, end, _values, start, _size-end);
		_size -= (end-start);		
		
		decrPtr(r+1, end-start);
	}

	@Override
	public void sort() {
		int rlen = numRows();
		for( int i=0; i<rlen && pos(i)<_size; i++ )
			sort(i);
	}

	@Override
	public void sort(int r) {
		int pos = pos(r);
		int len = size(r);
				
		if( len<=100 || !SortUtils.isSorted(pos, pos+len, _indexes) )
			SortUtils.sortByIndex(pos, pos+len, _indexes, _values);
	}

	@Override
	public double get(int r, int c) {
		int pos = pos(r);
		int len = size(r);
		
		//search for existing col index in [pos,pos+len)
		int index = Arrays.binarySearch(_indexes, pos, pos+len, c);		
		return (index >= 0) ? _values[index] : 0;
	}
	
	@Override 
	public SparseRow get(int r) {
		int pos = pos(r);
		int len = size(r);
		
		SparseRow row = new SparseRow(len);
		System.arraycopy(_indexes, pos, row.indexes(), 0, len);
		System.arraycopy(_values, pos, row.values(), 0, len);
		row.setSize(len);
		
		return row;
	}
	
	@Override
	public int posFIndexLTE(int r, int c) {
		int pos = pos(r);
		int len = size(r);
		
		//search for existing col index in [pos,pos+len)
		int index = Arrays.binarySearch(_indexes, pos, pos+len, c);
		if( index >= 0  )
			return (index < pos+len) ? index : -1;
		
		//search lt col index (see binary search)
		index = Math.abs( index+1 );
		return (index-1 >= pos) ? index-1 : -1;
	}

	@Override
	public int posFIndexGTE(int r, int c) {
		int pos = pos(r);
		int len = size(r);
		
		//search for existing col index
		int index = Arrays.binarySearch(_indexes, pos, pos+len, c);
		if( index >= 0  )
			return (index < pos+len) ? index : -1;
		
		//search gt col index (see binary search)
		index = Math.abs( index+1 );
		return (index < pos+len) ? index : -1;
	}

	@Override
	public int posFIndexGT(int r, int c) {
		int pos = pos(r);
		int len = size(r);
		
		//search for existing col index
		int index = Arrays.binarySearch(_indexes, pos, pos+len, c);
		if( index >= 0  )
			return (index+1 < pos+len) ? index+1 : -1;
		
		//search gt col index (see binary search)
		index = Math.abs( index+1 );
		return (index < pos+len) ? index : -1;
	}
	
	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append("SparseBlockCSR: rlen=");
		sb.append(numRows());
		sb.append(", nnz=");
		sb.append(size());
		sb.append("\n");
		for( int i=0; i<numRows(); i++ ) {
			sb.append("row +");
			sb.append(i);
			sb.append(": ");
			//append row
			int pos = pos(i);
			int len = size(i);
			for(int j=pos; j<pos+len; j++) {
				sb.append(_indexes[j]);
				sb.append(": ");
				sb.append(_values[j]);
				sb.append("\t");
			}
			sb.append("\n");
		}		
		
		return sb.toString();
	}
	
	///////////////////////////
	// private helper methods
	
	private int newCapacity(int minsize) {
		//compute new size until minsize reached
		double tmpCap = _values.length;
		while( tmpCap < minsize ) {
			tmpCap *= (tmpCap <= 1024) ? 
					RESIZE_FACTOR1 : RESIZE_FACTOR2;
		}
			
		return (int)Math.min(tmpCap, Integer.MAX_VALUE);
	}
	
	/**
	 * 
	 */
	private void resize() {
		//resize by at least by 1
		int newCap = newCapacity(_values.length+1);
		resizeCopy(newCap);
	}
	
	/**
	 * 
	 * @param minsize
	 */
	private void resize(int minsize) {
		int newCap = newCapacity(minsize);
		resizeCopy(newCap);
	}
	
	/**
	 * 
	 * @param capacity
	 */
	private void resizeCopy(int capacity) {
		//reallocate arrays and copy old values
		_indexes = Arrays.copyOf(_indexes, capacity);
		_values = Arrays.copyOf(_values, capacity);
	}
	
	/**
	 * 
	 * @param ix
	 * @param c
	 * @param v
	 */
	private void resizeAndInsert(int ix, int c, double v) {
		//compute new size
		int newCap = newCapacity(_values.length+1);
		
		int[] oldindexes = _indexes;
		double[] oldvalues = _values;
		_indexes = new int[newCap];
		_values = new double[newCap];
		
		//copy lhs values to new array
		System.arraycopy(oldindexes, 0, _indexes, 0, ix);
		System.arraycopy(oldvalues, 0, _values, 0, ix);
		
		//copy rhs values to new array
		System.arraycopy(oldindexes, ix, _indexes, ix+1, _size-ix);
		System.arraycopy(oldvalues, ix, _values, ix+1, _size-ix);
		
		//insert new value
		insert(ix, c, v);
	}
	
	/**
	 * 
	 * @param ix
	 * @param c
	 * @param v
	 */
	private void shiftRightAndInsert(int ix, int c, double v)  {		
		//overlapping array copy (shift rhs values right by 1)
		System.arraycopy(_indexes, ix, _indexes, ix+1, _size-ix);
		System.arraycopy(_values, ix, _values, ix+1, _size-ix);
		
		//insert new value
		insert(ix, c, v);
	}
	
	/**
	 * 
	 * @param index
	 */
	private void shiftLeftAndDelete(int ix)
	{
		//overlapping array copy (shift rhs values left by 1)
		System.arraycopy(_indexes, ix+1, _indexes, ix, _size-ix-1);
		System.arraycopy(_values, ix+1, _values, ix, _size-ix-1);
		_size--;
	}

	/**
	 * 
	 * @param ix
	 * @param n
	 */
	private void shiftRightByN(int ix, int n) 
	{		
		//overlapping array copy (shift rhs values right by 1)
		System.arraycopy(_indexes, ix, _indexes, ix+n, _size-ix);
		System.arraycopy(_values, ix, _values, ix+n, _size-ix);
		_size += n;
	}
	
	/**
	 * 
	 * @param ix
	 * @param n
	 */
	private void shiftLeftByN(int ix, int n)
	{
		//overlapping array copy (shift rhs values left by n)
		System.arraycopy(_indexes, ix, _indexes, ix-n, _size-ix);
		System.arraycopy(_values, ix, _values, ix-n, _size-ix);
		_size -= n;
	}
	
	/**
	 * 
	 * @param ix
	 * @param c
	 * @param v
	 */
	private void insert(int ix, int c, double v) {
		_indexes[ix] = c;
		_values[ix] = v;
		_size++;	
	}
	
	/**
	 * 
	 * @param rl
	 */
	private void incrPtr(int rl) {
		incrPtr(rl, 1);
	}
	
	/**
	 * 
	 * @param rl
	 * @param cnt
	 */
	private void incrPtr(int rl, int cnt) {
		int rlen = numRows();
		for( int i=rl; i<rlen+1; i++ )
			_ptr[i]+=cnt;
	}
	
	/**
	 * 
	 * @param rl
	 */
	private void decrPtr(int rl) {
		decrPtr(rl, 1);
	}
	
	/**
	 * 
	 * @param rl
	 * @param cnt
	 */
	private void decrPtr(int rl, int cnt) {
		int rlen = numRows();
		for( int i=rl; i<rlen+1; i++ )
			_ptr[i]-=cnt;
	}
	
	/**
	 * Get raw access to underlying array of row pointers
	 * For use in GPU code
	 * @return
	 */
	public int[] rowPointers() {
		return _ptr;
	}
	
	/** 
	 * Get raw access to underlying array of column indices
	 * For use in GPU code
	 * @return
	 */
	public int[] indexes() {
		return _indexes;
	}
	
	/**
	 * Get raw access to underlying array of values
	 * For use in GPU code
	 * @return
	 */
	public double[] values() {
		return _values;
	}
}
