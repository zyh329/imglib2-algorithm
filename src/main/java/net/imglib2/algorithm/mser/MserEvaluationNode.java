package net.imglib2.algorithm.mser;

import java.util.ArrayList;
import java.util.Comparator;

import net.imglib2.algorithm.componenttree.pixellist.PixelList;
import net.imglib2.type.Type;

final class MserEvaluationNode< T extends Type< T > >
{
	/**
	 * Threshold value of the connected component.
	 */
	final T value;

	/**
	 * Size (number of pixels) of the connected component.
	 */
	final long size;

	/**
	 * Pixels in the component.
	 */
	final PixelList pixelList;

	/**
	 * Children of this {@link MserEvaluationNode} in the component tree.
	 */
	private final ArrayList< MserEvaluationNode< T > > children;

	/**
	 * The child in the component tree from which we inherit the component size history.
	 */
	private final MserEvaluationNode< T > historyChild;

	/**
	 * Parent of this {@link MserEvaluationNode} in the component tree.
	 */
	private MserEvaluationNode< T > parent;
	
	/**
	 * MSER score : |Q_{i+\Delta} - Q_i| / |Q_i|. 
	 */
	double score;

	/**
	 * Whether the {@link #score} is valid.
	 * (Otherwise it has not or cannot be computed.)
	 */
	private boolean isScoreValid;
	
	/**
	 * Number of dimensions in the image.
	 */
	final int n;

	/**
	 * Mean of pixel positions (x, y, z, ...).
	 */
	final double[] mean;

	/**
	 * Independent elements of the covariance of pixel positions (xx, xy, xz, ..., yy, yz, ..., zz, ...).
	 */
	final double[] cov;

	/**
	 * {@link Mser}s associated to this region or its children. To build up the MSER
	 * tree.
	 */
	final ArrayList< Mser< T > > mserThisOrChildren;

	MserEvaluationNode( final MserComponentIntermediate< T > component, final Comparator< T > comparator, final ComputeDeltaValue< T > delta, final MserTree< T > tree )
	{
		value = component.getValue().copy();
		pixelList = new PixelList( component.pixelList );
		size = pixelList.size();

		children = new ArrayList< MserEvaluationNode< T > >();
		MserEvaluationNode< T > node = component.getEvaluationNode();
		long historySize = 0;
		if ( node != null )
		{
			historySize = node.size;
			node = new MserEvaluationNode< T >( node, value, comparator, delta );
			children.add( node );
			node.setParent( this );
		}

		MserEvaluationNode< T > historyWinner = node;
		for ( MserComponentIntermediate< T > c : component.children )
		{
			node = new MserEvaluationNode< T >( c.getEvaluationNode(), value, comparator, delta );
			children.add( node );
			node.setParent( this );
			if ( c.size() > historySize )
			{
				historyWinner = node;
				historySize = c.size();
			}
		}
		
		historyChild = historyWinner;
		
		n = component.n;
		mean = new double[ n ];
		cov = new double[ ( n * (n+1) ) / 2 ];
		for ( int i = 0; i < n; ++i )
			mean[ i ] = component.sumPos[ i ] / size;
		int k = 0;
		for ( int i = 0; i < n; ++i )
			for ( int j = i; j < n; ++j )
			{
				cov[ k ] = component.sumSquPos[ k ] / size - mean[ i ] * mean[ j ];
				++k;
			}

		component.setEvaluationNode( this );
		isScoreValid = computeMserScore( delta, comparator, false );
		if ( isScoreValid )
			for ( MserEvaluationNode< T > a : children )
				a.evaluateLocalMinimum( tree, delta, comparator );

		if ( children.size() == 1 )
			mserThisOrChildren = children.get( 0 ).mserThisOrChildren;
		else
		{
			mserThisOrChildren = new ArrayList< Mser< T > >();
			for ( MserEvaluationNode< T > a : children )
				mserThisOrChildren.addAll( a.mserThisOrChildren );
		}
	}

	private MserEvaluationNode( final MserEvaluationNode< T > child, final T value, final Comparator< T > comparator, final ComputeDeltaValue< T > delta )
	{
		children = new ArrayList< MserEvaluationNode< T > >();
		children.add( child );
		child.setParent( this );

		historyChild = child;
		size = child.size;
		pixelList = child.pixelList;
		this.value = value;
		n = child.n;
		mean = child.mean;
		cov = child.cov;

		isScoreValid = computeMserScore( delta, comparator, true );
//		All our children are non-intermediate, and
//		non-intermediate nodes are never minimal because their score is
//		never smaller than that of the parent intermediate node.
//		if ( isScoreValid )
//			child.evaluateLocalMinimum( minimaProcessor, delta );

		mserThisOrChildren = child.mserThisOrChildren;
	}

	private void setParent( MserEvaluationNode< T > node )
	{
		parent = node;
	}

	/**
	 * Evaluate the mser score at this connected component. This may fail if the
	 * connected component tree is not built far enough down from the current
	 * node. The mser score is computed as |Q_{i+delta} - Q_i| / |Q_i|, where
	 * Q_i is this component and Q_{i+delta} is the component delta steps down
	 * the component tree (threshold level is delta lower than this).
	 * 
	 * @param delta
	 * @param isIntermediate
	 *            whether this is an intermediate node. This influences the
	 *            search for the Q_{i+delta} in the following way. If a node
	 *            with value equal to i+delta is found, then this is a
	 *            non-intermediate node and there is an intermediate node with
	 *            the same value below it. If isIntermediate is true Q_{i+delta}
	 *            is set to the intermediate node. (The other possibility is,
	 *            that we find a node with value smaller than i+delta, i.e.,
	 *            there is no node with that exact value. In this case,
	 *            isIntermediate has no influence.)
	 */
	private boolean computeMserScore( final ComputeDeltaValue< T > delta, final Comparator< T > comparator, final boolean isIntermediate )
	{
		// we are looking for a precursor node with value == (this.value - delta)
		final T valueMinus = delta.valueMinusDelta( value );

		// go back in history until we find a node with (value <= valueMinus)
		MserEvaluationNode< T > node = historyChild;
		while ( node != null  &&  comparator.compare( node.value, valueMinus ) > 0 )
			node = node.historyChild;
		if ( node == null )
			// we cannot compute the mser score because the history is too short.
			return false;
		if ( isIntermediate && comparator.compare( node.value, valueMinus ) == 0 && node.historyChild != null )
			node = node.historyChild;
		score = ( size - node.size ) / ( ( double ) size );
		return true;		
	}

	/**
	 * Check whether the mser score is a local minimum at this connected
	 * component. This may fail if the mser score for this component, or the
	 * previous one in the branch are not available. (Note, that this is only
	 * called, when the mser score for the next component in the branch is
	 * available.)
	 */
	private void evaluateLocalMinimum( final MserTree< T > tree, final ComputeDeltaValue< T > delta, final Comparator< T > comparator )
	{
		if ( isScoreValid )
		{
			MserEvaluationNode< T > below = historyChild;
			while ( below.isScoreValid && below.size == size )
				below = below.historyChild;
			if ( below.isScoreValid )
			{
					below = below.historyChild;
				if ( ( score <= below.score ) && ( score < parent.score ) )
					tree.foundNewMinimum( this );
			}
			else
			{
				final T valueMinus = delta.valueMinusDelta( value );
				if ( comparator.compare( valueMinus, below.value ) > 0 )
					// we are just above the bottom of a branch and this components
					// value is high enough above the bottom value to make its score=0.
					// so let's pretend we found a minimum here...
					tree.foundNewMinimum( this );
			}
		}
	}

	@Override
	public String toString()
	{
		String s = "SimpleMserEvaluationNode";
		s += ", size = " + size;
		s += ", history = [";
		MserEvaluationNode< T > node = historyChild;
		boolean first = true;
		while ( node != null )
		{
			if ( first )
				first = false;
			else
				s += ", ";	
			s += "(" + node.value + "; " + node.size;
			if ( node.isScoreValid )
				s += " s " + node.score + ")";
			else
				s += " s --)";
			node = node.historyChild;
		}
		s += "]";
		return s;
	}
}
