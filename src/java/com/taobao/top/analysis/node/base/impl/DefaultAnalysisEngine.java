/**
 * 
 */
package com.taobao.top.analysis.node.base.impl;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import com.taobao.top.analysis.job.JobTask;
import com.taobao.top.analysis.job.TaskExecuteInfo;
import com.taobao.top.analysis.node.base.IAnalysisEngine;
import com.taobao.top.analysis.node.base.IInputAdaptor;
import com.taobao.top.analysis.node.base.IOutputAdaptor;
import com.taobao.top.analysis.node.base.IReportMap;
import com.taobao.top.analysis.node.base.IReportReduce;
import com.taobao.top.analysis.statistics.data.Alias;
import com.taobao.top.analysis.statistics.data.EntryValueOperator;
import com.taobao.top.analysis.statistics.data.InnerKey;
import com.taobao.top.analysis.statistics.data.ReportEntry;
import com.taobao.top.analysis.statistics.data.ReportEntryValueType;
import com.taobao.top.analysis.util.AnalysisConstants;
import com.taobao.top.analysis.util.ReportUtil;

/**
 * @author fangweng
 * @Email fangweng@taobao.com
 * 2011-11-24
 *
 */
public class DefaultAnalysisEngine implements IAnalysisEngine{
	private static final Log logger = LogFactory.getLog(DefaultAnalysisEngine.class);
	
	List<IInputAdaptor> inputAdaptors;
	List<IOutputAdaptor> outputAdaptors;

	public DefaultAnalysisEngine()
	{
		inputAdaptors = new ArrayList<IInputAdaptor>();
		outputAdaptors = new ArrayList<IOutputAdaptor>();
	}
	
	@Override
	public void addInputAdaptor(IInputAdaptor inputAdaptor) {
		inputAdaptors.add(inputAdaptor);
	}

	@Override
	public void removeInputAdaptor(IInputAdaptor inputAdaptor) {
		inputAdaptors.remove(inputAdaptor);
	}

	@Override
	public void addOutputAdaptor(IOutputAdaptor outputAdaptor) {
		outputAdaptors.add(outputAdaptor);
	}

	@Override
	public void removeOutputAdaptor(IOutputAdaptor outputAdaptor) {
		outputAdaptors.remove(outputAdaptor);
	}

	@Override
	public void doAnalysis(JobTask jobtask) throws UnsupportedEncodingException, IOException {
		
		InputStream in = null;
		
		try
		{
			for(IInputAdaptor inputAdaptor : inputAdaptors)
			{
				if (inputAdaptor.ignore(jobtask.getInput()))
					continue;
				
				in = inputAdaptor.getInputFormJob(jobtask);
				
				if (in != null)
					break;
			}
			
			analysis(in,jobtask);
			
			for(IOutputAdaptor outputAdaptor : outputAdaptors)
			{
				if (outputAdaptor.ignore(jobtask.getOutput()))
					continue;
				
				outputAdaptor.sendResultToOutput(jobtask);
			}
		}
		finally
		{
			if (in != null)
				try {
					in.close();
				} catch (IOException e) {
					logger.error(e);
				}
		}

	}
	
	void analysis(InputStream in,JobTask jobtask) throws UnsupportedEncodingException
	{
		
		String encoding = jobtask.getInputEncoding();
		String splitRegex = jobtask.getSplitRegex();
		TaskExecuteInfo taksExecuteInfo = jobtask.getTaskExecuteInfo();
		Map<String, ReportEntry> entryPool = jobtask.getStatisticsRule().getEntryPool();
		
		BufferedReader reader = new BufferedReader(new InputStreamReader(in, encoding));
		
		int normalLine = 0;//正常的行数
		int emptyLine=0;//拉取空行的次数
		int exceptionLine=0;//一行中，只要entry有异常，该行就是存在异常的行。
		int size = 0;
		String record;
		
		ReportEntry entry = null;
		
		long beg = System.currentTimeMillis();
		
		try 
		{
			while ((record = reader.readLine()) != null) 
			{
				boolean failure=false;
				
				try
				{
					if (record == null || "".equals(record)) 
					{
						emptyLine++;
						continue;
					}
					
					size += record.length();
					
					String[] contents = StringUtils.splitByWholeSeparator(record, splitRegex);
					Iterator<String> keys = entryPool.keySet().iterator();
					List<ReportEntry> childEntrys = new ArrayList<ReportEntry>();
					Map<String, Object> valueTempPool = new HashMap<String, Object>();
					
					while (keys.hasNext()) 
					{
						try 
						{
							String key = keys.next();
							entry = entryPool.get(key);
							
							if (entry.isLazy())
								continue;
							
							if (entry.getParent() != null)
							{
								childEntrys.add(entry);
								continue;
							}
							
							processSingleLine(entry, contents, valueTempPool,jobtask);
							
						} 
						catch (Throwable e) 
						{
							if(!failure) 
								exceptionLine++;
							
							failure=true;
							
							if (logger.isInfoEnabled())
								logger.info(new StringBuilder().append("Entry :")
									.append(entry.getId()).append("\r\n record: ")
									.append(record).toString(), e);
						}
					}
					
					for (Iterator<ReportEntry> iterator = childEntrys.iterator(); iterator.hasNext();)
					{
						try 
						{
							entry = iterator.next();
							processSingleLine(entry, contents, valueTempPool,jobtask);
						}
						catch (Throwable e) 
						{
							if(!failure) 
								exceptionLine++;
							
							failure=true;
							
							if (logger.isInfoEnabled())
								logger.info(
									new StringBuilder().append("Entry :")
											.append(entry.getId())
											.append("\r\n record: ").append(record)
											.toString(), e);
						}
					}

					if(!failure) 
						normalLine++;
					
				}
				catch(Throwable t)
				{
					if(!failure) 
						exceptionLine++;
					
					if (logger.isInfoEnabled())
						logger.info(
							new StringBuilder()
									.append("\r\n record: ").append(record)
									.toString(), t);
				}
			}
			
		}
		catch (Throwable ex) {
			taksExecuteInfo.setSuccess(false);
			logger.error(ex);
		} 
		finally 
		{
			if (reader != null) 
			{
				try {
					reader.close();
					reader = null;
				} 
				catch (Throwable ex) {
					logger.error(ex);
				}
			}

			taksExecuteInfo.setAnalysisConsume(System.currentTimeMillis() - beg);
			taksExecuteInfo.setEmptyLine(emptyLine);
			taksExecuteInfo.setErrorLine(exceptionLine);
			taksExecuteInfo.setJobDataSize(size*2);
			taksExecuteInfo.setTotalLine(normalLine+exceptionLine+emptyLine);
			//taksExecuteInfo.setWorkerIp(workerIp)
			
			if (logger.isWarnEnabled())
				logger.warn(new StringBuilder("jobtask ").append(jobtask.getTaskId())
					.append(",normal line count: ").append(normalLine)
					.append(",exception line count:").append(exceptionLine)
					.append(",empty line:").append(emptyLine).toString());
		}
		
	}
	
	
	public void processSingleLine(ReportEntry entry, String[] contents,
			Map<String, Object> valueTempPool,JobTask jobtask){
		
		boolean isChild = true;
		
		if (entry.getParent() == null) 
		{
			isChild = false;
		}
		
		Map<String, Map<String, Object>> entryResult = jobtask.getResults();
		Map<String, ReportEntry> entryPool = jobtask.getStatisticsRule().getEntryPool();
		Map<String, Alias> aliasPool = jobtask.getStatisticsRule().getAliasPool();
		List<InnerKey> innerKeyPool = jobtask.getStatisticsRule().getInnerKeyPool();
		Map<String, ReportEntry> parentEntryPool = jobtask.getStatisticsRule().getParentEntryPool();

		Map<String, Object> mapResult = entryResult.get(entry.getId());

		if (mapResult == null) {
			mapResult = new HashMap<String, Object>();
			entryResult.put(entry.getId(), mapResult);
		}

		String key = null;
		Object value = null;
		// 如果是孩子
		if (isChild) {
			String parent = entry.getParent();
			ReportEntry parentEntry = entryPool.get(parent);
			value = valueTempPool.get(parentEntry.getId());
			if (value == null) {
				return;
			} else {
				if (entry.getKeys() == null) {
					entry.setKeys(parentEntry.getKeys());
					entry.setValueType(parentEntry.getValueType());
					if (entry.getFormatStack() == null) {
						entry.setFormatStack(parentEntry.getFormatStack());
					}
				}

			}
		}

		// 增加全局MapClass的处理
		if (entry.getGlobalMapClass() != null
				&& entry.getGlobalMapClass().size() > 0) {
			for (String mc : entry.getGlobalMapClass()) {
				IReportMap mapClass = ReportUtil.getInstance(IReportMap.class,
						Thread.currentThread().getContextClassLoader(), mc,
						true);

				key = mapClass.generateKey(entry, contents, aliasPool, null,innerKeyPool);

				if (key.equals(AnalysisConstants.IGNORE_PROCESS)) {
					return;
				}
			}
		}

		if (entry.getMapClass() == null || "".equals(entry.getMapClass())) {
			if (key == null)
				key = generateKey(entry, contents,innerKeyPool);
		} else {
			IReportMap mapClass = ReportUtil.getInstance(IReportMap.class,
					Thread.currentThread().getContextClassLoader(),
					entry.getMapClass(), true);

			key = mapClass.generateKey(entry, contents, aliasPool, null,innerKeyPool);
		}

		// 该内容忽略，不做统计
		if (key.equals(AnalysisConstants.IGNORE_PROCESS)) {
			return;
		}

		if (key.equals(""))
			throw new java.lang.RuntimeException("JobWorker create key error!");
		

		if (!isChild) {
			if (entry.getReduceClass() != null
					&& !"".equals(entry.getReduceClass())) {
				IReportReduce reduceClass = ReportUtil.getInstance(
						IReportReduce.class, Thread.currentThread()
								.getContextClassLoader(), entry
								.getReduceClass(), true);

				value = reduceClass.generateValue(entry, contents, aliasPool);
			} else
				value = generateValue(entry, contents);

		} else {
			if (value.equals("NULL")) {
				value = null;
			}

		}
		
		// value filter inject
		if (entry.getValueType() != ReportEntryValueType.COUNT
				&& entry.getValuefilterStack() != null
				&& entry.getValuefilterStack().size() > 0) {
			if (!ReportUtil.checkValue(entry.getValuefilterOpStack(),
					entry.getValuefilterStack(), value))
				return;
		}

		if (!isChild) {

			if (parentEntryPool.get(entry.getId()) != null) {
				if (value == null) {
					valueTempPool.put(entry.getId(), "NULL");
				} else {
					valueTempPool.put(entry.getId(), value);
				}
			}
		}

		switch (entry.getValueType()) {
		case AVERAGE:
			if (value == null)
				return;
			value = Double.parseDouble(value.toString());
			String sumkey = new StringBuilder().append(AnalysisConstants.PREF_SUM).append(key)
					.toString();
			String countkey = new StringBuilder().append(AnalysisConstants.PREF_COUNT).append(key)
					.toString();
			Double sum = (Double) mapResult.get(sumkey);
			Double count = (Double) mapResult.get(countkey);
			if (sum == null || count == null) {
				mapResult.put(sumkey, (Double) value);
				mapResult.put(countkey, (Double) 1.0);
				mapResult.put(key, (Double) value);
			} else {
				// 再次验证一下
				Object tempvalue = ((Double) value + sum)
						/ (Double) (count + 1);
				mapResult.put(sumkey, (Double) value + sum);
				mapResult.put(countkey, (Double) (count + 1));
				mapResult.put(key, tempvalue);
			}
			break;
		case SUM:

			if (value == null)
				return;

			if (value instanceof String) {
				value = Double.parseDouble((String) value);
			}

			Double _sum = (Double) mapResult.get(key);

			if (_sum == null)
				mapResult.put(key, (Double) value);
			else
				mapResult.put(key, (Double) value + _sum);

			break;

		case MIN:
			if (value == null)
				return;

			if (value instanceof String) {
				value = Double.parseDouble((String) value);
			}

			Double min = (Double) mapResult.get(key);

			if (min == null)
				mapResult.put(key, (Double) value);
			else if ((Double) value < min)
				mapResult.put(key, (Double) value);

			break;

		case MAX:
			if (value == null)
				return;

			if (value instanceof String) {
				value = Double.parseDouble((String) value);
			}

			Double max = (Double) mapResult.get(key);

			if (max == null)
				mapResult.put(key, (Double) value);
			else if ((Double) value > max)
				mapResult.put(key, (Double) value);

			break;

		case COUNT:

			Double total = (Double) mapResult.get(key);

			if (total == null)
				mapResult.put(key, (Double) 1.0);
			else
				mapResult.put(key, total + 1);

			break;

		case PLAIN:

			Object o = mapResult.get(key);

			if (o == null)
				mapResult.put(key, value);

			break;

		}

	}
	
	public  Object generateValue(ReportEntry entry, String[] contents)
	{
		Object result = null;

		double left = 0;

		if (entry.getBindingStack() != null
				&& entry.getBindingStack().size() > 0) {
			List<String> bindingStack = entry.getBindingStack();

			if (bindingStack.size() > 1) {
				if (bindingStack.get(0).startsWith("#"))
					left = Double.valueOf(bindingStack.get(0).substring(1));
				else {
					if (Integer.valueOf(bindingStack.get(0)) - 1 >= contents.length)
						return result;

					left = Double.valueOf(contents[Integer.valueOf(bindingStack
							.get(0)) - 1]);
				}

				double right = 0;

				int size = bindingStack.size();

				for (int i = 0; i < size - 1; i++) {
					if (bindingStack.get(i + 1).startsWith("#"))
						right = Double.valueOf(bindingStack.get(i + 1)
								.substring(1));
					else {
						if (Integer.valueOf(bindingStack.get(i + 1)) - 1 >= contents.length)
							return result;

						right = Double.valueOf(contents[Integer
								.valueOf(bindingStack.get(i + 1)) - 1]);
					}

					if (entry.getOperatorStack().get(i)
							.equals(EntryValueOperator.PLUS.toString()))
						left += right;

					if (entry.getOperatorStack().get(i)
							.equals(EntryValueOperator.MINUS.toString()))
						left -= right;

					if (entry.getOperatorStack().get(i)
							.equals(EntryValueOperator.RIDE.toString()))
						left = left * right;

					if (entry.getOperatorStack().get(i)
							.equals(EntryValueOperator.DIVIDE.toString()))
						left = left / right;

				}

				result = left;
			} else {
				if (bindingStack.get(0).startsWith("#"))
					result = Double.valueOf(bindingStack.get(0).substring(1));
				else {
					if (Integer.valueOf(bindingStack.get(0)) - 1 >= contents.length)
						return result;

					result = contents[Integer.valueOf(bindingStack.get(0)) - 1];
				}

			}

		}

		return result;
	}
	
	
	
	/**
	 * 根据定义获取对应日志行产生的key
	 * 
	 * @param entry
	 * @param contents
	 * @return
	 */
	private  String generateKey(ReportEntry entry, String[] contents,List<InnerKey> innerKeyPool) {
		StringBuilder key = new StringBuilder();

		try {
			boolean checkResult = false;

			if (entry.getConditionKStack() != null
					&& entry.getConditionKStack().size() > 0) {
				for (int i = 0; i < entry.getConditionKStack().size(); i++) {

					Object conditionKey = entry.getConditionKStack().get(i);
					String operator = entry.getConditionOpStack().get(i);
					String conditionValue = entry.getConditionVStack().get(i);
					int k = -1;

					// 长度condition特殊处理，没有指定的key列
					if (!conditionKey.equals(AnalysisConstants.RECORD_LENGTH)) {
						k = (Integer) conditionKey;
					}

					checkResult = checkKeyCondition(operator, k,
							conditionValue, contents);

					if (entry.isAndCondition() && !checkResult)
						return AnalysisConstants.IGNORE_PROCESS;

					if (!entry.isAndCondition() && checkResult)
						break;
				}
			}

			if (!entry.isAndCondition() && !checkResult)
				return AnalysisConstants.IGNORE_PROCESS;

			for (String c : entry.getKeys()) {
				// 全局统计，没有key
				if (c.equals(AnalysisConstants.GLOBAL_KEY))
					return AnalysisConstants.GLOBAL_KEY;

				key.append(innerKeyReplace(c,contents[Integer.valueOf(c) - 1],innerKeyPool)).append(AnalysisConstants.SPLIT_KEY);
			}

		} catch (Exception ex) {
			return AnalysisConstants.IGNORE_PROCESS;
		}

		return key.toString();
	}
	
	/**
	 * 返回是否符合条件
	 * 
	 * @param operator
	 * @param conditionKey
	 * @param conditionValue
	 * @param contents
	 * @return
	 */
	private  boolean checkKeyCondition(String operator, int conditionKey,
			String conditionValue, String[] contents) {
		boolean result = false;

		if (operator.equals(AnalysisConstants.CONDITION_EQUAL)) {
			if (conditionKey > 0)
				result = contents[conditionKey - 1].equals(conditionValue);
			else
				result = contents.length == Integer.valueOf(conditionValue);
		} else if (operator.equals(AnalysisConstants.CONDITION_NOT_EQUAL)) {
			if (conditionKey > 0)
				result = !contents[conditionKey - 1].equals(conditionValue);
			else
				result = contents.length != Integer.valueOf(conditionValue);
		} else {
			double cmpValue = 0;

			if (conditionKey > 0)
				cmpValue = Double.valueOf(contents[conditionKey - 1])
						- Double.valueOf(conditionValue);
			else
				cmpValue = contents.length - Integer.valueOf(conditionValue);

			if (operator.equals(AnalysisConstants.CONDITION_EQUALORGREATER))
				return cmpValue >= 0;

			if (operator.equals(AnalysisConstants.CONDITION_EQUALORLESSER))
				return cmpValue <= 0;

			if (operator.equals(AnalysisConstants.CONDITION_GREATER))
				return cmpValue > 0;

			if (operator.equals(AnalysisConstants.CONDITION_LESSER))
				return cmpValue < 0;

		}

		return result;
	}
	
	private String innerKeyReplace(String key,String value,List<InnerKey> innerKeyPool)
	{
		String result = value;
		
		if (innerKeyPool == null || (innerKeyPool != null && innerKeyPool.size() == 0))
			return result;
		
		for(InnerKey ik : innerKeyPool)
		{
			if (ik.getKey().equals(key))
			{
				if (ik.getInnerKeys().get(value) != null)
					result = ik.getInnerKeys().get(value);
				
				break;
			}
		}
		
		return result;
	}

}