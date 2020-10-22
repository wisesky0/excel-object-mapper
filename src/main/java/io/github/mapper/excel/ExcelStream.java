/*
 * code https://github.com/mohsen-mahmoudi/excel-object-mapping
 */
package io.github.mapper.excel;

import io.github.mapper.excel.util.EachFieldCallback;
import io.github.mapper.excel.util.ReflectionUtils;
import io.github.mapper.excel.util.WorkbookCallback;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * @author redcrow
 * 
 * @author Mohsen.Mahmoudi
 * 
 */
public class ExcelStream {

	private static final Logger LOG = LoggerFactory.getLogger(ExcelStream.class);

	private Class<?> clazz;
	private final XSSFWorkbook workbook;

	private int[] sheet;
	private boolean handleCellExceptions = false;
	private boolean mustMapRowThatCellsHaveException = false;
	private boolean cellHasException = false;
	private List listExceptions;
	private WorkbookCallback callback;
	private XSSFCellStyle cellExceptionBackground;
	private Map<String, Integer> fieldToColumnIndex = new HashMap<>();
	private Integer headerRowNumber = 0;
	private Integer startRowNumber;
	private Integer endRowNumber;

	private ExcelStream(File excelFile) throws Throwable {
		this.workbook = new XSSFWorkbook(excelFile);
	}

	private ExcelStream(FileInputStream excelFile) throws Throwable {
		this.workbook = new XSSFWorkbook(excelFile);
	}

	private ExcelStream(byte[] excelByteFile) throws Throwable {
		this.workbook = new XSSFWorkbook(new ByteArrayInputStream(excelByteFile));
	}

	public static ExcelStream mapFromExcel(File excelFile) throws Throwable {
		return new ExcelStream(excelFile);
	}

	public static ExcelStream mapFromExcel(FileInputStream excelFile) throws Throwable {
		return new ExcelStream(excelFile);
	}

	public static ExcelStream mapFromExcel(byte[] excelByteFile) throws Throwable {
		return new ExcelStream(excelByteFile);
	}

	public ExcelStream toObjectOf(Class<?> clazz) {
		this.clazz = clazz;
		return this;
	}

	public ExcelStream mapFieldFrom(Map<String, Integer> fieldToColumnIndex) {
		this.fieldToColumnIndex = fieldToColumnIndex;
		return this;
	}

	public ExcelStream fromSheet(int... sheetNumbers) {
		this.sheet = sheetNumbers;
		return this;
	}

	public ExcelStream fromSheet(String... sheetNames) {
		List<Integer> integers = Arrays.asList(sheetNames).stream().map(this.workbook::getSheetIndex).collect(Collectors.toList());
		int[] sheets = new int[integers.size()];
		for(int i = 0; i < sheets.length; i++) {
			sheets[i] = integers.get(i);
		}
		this.sheet = sheets;
		return this;
	}

	public ExcelStream handleCellExceptions() {
		this.handleCellExceptions = true;
		return this;
	}

	public ExcelStream mapRowThatCellsHaveException() {
		this.mustMapRowThatCellsHaveException = true;
		return this;
	}

	public ExcelStream getAllExceptions(List listExceptions) {
		this.listExceptions = listExceptions;
		return this;
	}

	public ExcelStream headerRowNumber(Integer headerRowNumber) {
		this.headerRowNumber = headerRowNumber;
		return this;
	}

	public ExcelStream startRowNumber(Integer startRowNumber) {
		this.startRowNumber = startRowNumber;
		return this;
	}

	public ExcelStream endRowNumber(Integer endRowNumber) {
		this.endRowNumber = endRowNumber;
		return this;
	}

	public ExcelStream getWorkbookExceptions(WorkbookCallback callback) {
		this.callback = callback;
		return this;
	}

	public ExcelStream cellExceptionsColor(IndexedColors indexColor) throws Throwable {
		cellExceptionBackground = this.workbook.createCellStyle();
		cellExceptionBackground.setFillForegroundColor(indexColor.index);
		cellExceptionBackground.setFillPattern(FillPatternType.SOLID_FOREGROUND);

		return this;
	}

	public <T> List<T> map() throws Throwable {
		List<T> items = new ArrayList<>();

		if (this.sheet == null) {
			for (int index = 0; index < this.workbook.getNumberOfSheets(); index++) {
				processSheet(items, index);
			}
		} else {
			for (int index = 0; index < this.sheet.length; index++) {
				processSheet(items, this.sheet[index]);
			}
		}

		if (this.handleCellExceptions) {
			this.callback.getWorkbook(this.workbook);
		}

		return items;
	}

	private Stream<Row> toStreamBySheet(int sheetNumber) {
		XSSFSheet sheet = this.workbook.getSheetAt(sheetNumber);
		return StreamSupport.stream(sheet.spliterator(), false);
	}

	private Stream<Row> toStreamBySheet(String sheetName) {
		XSSFSheet sheet = this.workbook.getSheet(sheetName);
		return StreamSupport.stream(sheet.spliterator(), false);
	}

	private <T> void processSheet(List<T> items, int sheetNumber) throws Throwable {
		XSSFSheet sheet = this.workbook.getSheetAt(sheetNumber);
		Iterator<Row> rowIterator = sheet.iterator();

		Map<Field, Integer> fieldToIndexMap = new HashMap<>();

		while (rowIterator.hasNext()) {
			Row row = rowIterator.next();
			this.cellHasException = false;

			if (row.getRowNum() == this.headerRowNumber) {
				if (this.fieldToColumnIndex.size() > 0) {
					readExcelHeaderFromMap(fieldToIndexMap);
				} else {
					readExcelHeaderFromAnnotations(row, fieldToIndexMap);
				}
			} else {

				if ((this.startRowNumber == null || row.getRowNum() >= this.startRowNumber)
						&& (this.endRowNumber == null || row.getRowNum() <= this.endRowNumber)) {
					T readExcelContent = (T) readExcelContent(row, fieldToIndexMap);

					if (!this.cellHasException || this.mustMapRowThatCellsHaveException) {
						items.add(readExcelContent);
					}

					if (this.listExceptions != null && this.handleCellExceptions && this.cellHasException) {
						this.listExceptions.add(readExcelContent);
					}
				}
			}
		}
	}

	private void readExcelHeaderFromMap(Map<Field, Integer> fieldToIndexMap) throws Throwable {
		Iterator<Entry<String, Integer>> iterator = this.fieldToColumnIndex.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<String, Integer> next = iterator.next();
			fieldToIndexMap.put(ReflectionUtils.mapNameToField(clazz, next.getKey()), next.getValue());
		}
	}

	private void readExcelHeaderFromAnnotations(final Row row, final Map<Field, Integer> fieldToIndexMap)
			throws Throwable {
		ReflectionUtils.eachFields(clazz, new EachFieldCallback() {
			@Override
			public void each(Field field, String name, Integer index) throws Throwable {
				if (name != null) {
					mapNameToIndex(field, name, row, fieldToIndexMap);
				} else {
					fieldToIndexMap.put(field, index);
				}
			}
		});
	}

	private void mapNameToIndex(Field field, String name, Row row, Map<Field, Integer> cells) {
		int idx = findIndexCellByName(name, row);
		if (idx != -1) {
			cells.put(field, idx);
		}
	}

	private int findIndexCellByName(String name, Row row) {
		Iterator<Cell> iterator = row.cellIterator();
		while (iterator.hasNext()) {
			Cell cell = iterator.next();
			if (getCellValue(cell).trim().equalsIgnoreCase(name)) {
				return cell.getColumnIndex();
			}
		}

		return -1;
	}

	private Object readExcelContent(final Row row, final Map<Field, Integer> fieldToIndexMap) throws Throwable {
		final Object instance = clazz.newInstance();

		Iterator<Entry<Field, Integer>> iterator = fieldToIndexMap.entrySet().iterator();
		while (iterator.hasNext()) {
			Entry<Field, Integer> next = iterator.next();
			Cell cell = row.getCell(next.getValue());
			try {
				ReflectionUtils.setValueOnField(instance, next.getKey(), getCellValue(cell));
			} catch (Exception e) {
				this.cellHasException = true;
				LOG.error("Error raise on Sheet: " + row.getSheet().getSheetName() + ", Row: " + row.getRowNum()
						+ ", Cell: " + cell.getColumnIndex());
				e.printStackTrace();
				if (this.handleCellExceptions) {
					setCellBackground(row, cell.getColumnIndex(), cellExceptionBackground);
				}
			}
		}

		return instance;
	}

	private String getCellValue(Cell cell) {
		if (cell == null) {
			return null;
		}

		String value = "";
		switch (cell.getCellType()) {
		case Cell.CELL_TYPE_BOOLEAN:
			value += String.valueOf(cell.getBooleanCellValue());
			break;
		case Cell.CELL_TYPE_NUMERIC:
			value += new BigDecimal(cell.getNumericCellValue()).toString();
			break;
		case Cell.CELL_TYPE_STRING:
			value += cell.getStringCellValue();
			break;
		}

		return value;
	}

	private static Boolean setCellBackground(Row row, int colNum, XSSFCellStyle cellBackground) {
		Cell cell = row.getCell(colNum);
		if (cell == null)
			row.createCell(colNum).setCellStyle(cellBackground);
		else
			row.getCell(colNum).setCellStyle(cellBackground);
		return false;
	}
}
