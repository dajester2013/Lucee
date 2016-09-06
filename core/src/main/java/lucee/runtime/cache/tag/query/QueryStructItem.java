package lucee.runtime.cache.tag.query;

import lucee.commons.digest.HashUtil;
import lucee.runtime.cache.tag.udf.UDFArgConverter;
import lucee.runtime.type.query.QueryStruct;

public class QueryStructItem extends QueryResultCacheItem {

	private static final long serialVersionUID = 7327671003736543783L;

	public final QueryStruct queryStruct;

	public QueryStructItem(QueryStruct queryStruct){
		super(queryStruct);
		this.queryStruct=queryStruct;
	}

	@Override
	public String getHashFromValue() {
		return Long.toString(HashUtil.create64BitHash(UDFArgConverter.serialize(queryStruct)));
	}
	

	public QueryStruct getQueryStruct() {
		return queryStruct;
	}


	@Override
	public Object duplicate(boolean deepCopy) {
		return new QueryStructItem((QueryStruct)queryStruct.duplicate(true));
	}

}