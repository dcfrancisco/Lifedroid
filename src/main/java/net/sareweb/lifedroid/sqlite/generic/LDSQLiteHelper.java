package net.sareweb.lifedroid.sqlite.generic;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import net.sareweb.lifedroid.annotation.LDEntity;
import net.sareweb.lifedroid.annotation.LDField;
import net.sareweb.lifedroid.exception.IntrospectionException;
import net.sareweb.lifedroid.model.LDObject;

import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.BooleanUtils;
import org.apache.commons.lang.StringUtils;
import org.omg.CORBA.OBJ_ADAPTER;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabase.CursorFactory;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;


public abstract class LDSQLiteHelper<T extends LDObject> extends
		SQLiteOpenHelper {
	
	public LDSQLiteHelper(Context context, String name, CursorFactory factory,int version) {
		super(context, name, factory, version);
		getWritableDatabase().close();
	}

	public void onCreate(SQLiteDatabase db) {
		Log.d(TAG, "Creating DB");
		composeCreateSQL();
		Log.d(TAG, "\tCreate sentence: " + _sqlCreate);
		db.execSQL(_sqlCreate);
	}
	
	public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
		db.execSQL("DROP TABLE IF EXISTS " + getTableName());
		Log.d(TAG, "Upgrading DB"); 
		composeCreateSQL();
		Log.d(TAG, "\tCreate sentence: " + _sqlCreate);
		db.execSQL(_sqlCreate);
	}
	
	public T persist(T t) throws IntrospectionException{
		Log.d(TAG, "persisting " + t.getId());
		if(t.getId()==null){
			t.setObjectStatus(OBJECT_STATUS_NEW);
			long id  = getWritableDatabase().insert(getTableName(), null, composeContentValues(t, false));
			t.setId(id);
		}
		else if(t.getObjectStatus().equals(OBJECT_STATUS_SYNCH)){
			long id  = getWritableDatabase().insert(getTableName(), null, composeContentValues(t, true));
			t.setId(id);
		}
		
		else{
			String[] ident = new String[] {String.valueOf(t.getId())};
			getWritableDatabase().update(getTableName(), composeContentValues(t, true), getIdFieldName()+"=?", ident);
		}
		close();
		return t;
	}

	public T getById(Long id){
		String[] ident = new String[] {id.toString()};
		Cursor cur = getReadableDatabase().query(getTableName(), getFieldNames(), getIdFieldName()+"=?", ident, null, null, null);
		if(cur==null || cur.getCount()==0){
			Log.d(TAG,"Object not found");
			return null;
		}
		cur.moveToFirst();
		return getObjectFromCursor(cur);
	}
	
	public List<T> query(String selection, String[] selectionArgs){
		//selection= selection+ " and objectStatus <> '" + OBJECT_STATUS_DELETED+ "'";
		Cursor c = getReadableDatabase().query(getTableName(), getFieldNames(), selection, selectionArgs, null, null, null);
		if(c==null)return null;
		ArrayList<T> results = new ArrayList<T>(c.getCount()); 
		
		while(c.moveToNext()){
			results.add(getObjectFromCursor(c));
			
		}
		close();
		return results;
	}
	
	
	public int delete(Long id){
		String[] whereArgs = {id.toString()};
		return this.getWritableDatabase().delete(getTableName(), getIdFieldName()+"=?", whereArgs);
	}
	
	public int deleteAll(){
		return this.getWritableDatabase().delete(getTableName(), null, null);
	}
	
	public void logicalDelete(Long id){
		String[] whereArgs = {id.toString()};
		ContentValues contentValues = new ContentValues();
		if(isRemote(id))contentValues.put("objectStatus", OBJECT_STATUS_DELETED_REMOTE);
		else contentValues.put("objectStatus", OBJECT_STATUS_DELETED_LOCAL);
		getWritableDatabase().update(getTableName(), contentValues, getIdFieldName()+"=?", whereArgs);
	}
	
	public boolean isRemote(Long id){
		T t = getById(id);
		if(t.getObjectStatus()!=null && (t.getObjectStatus().equals(OBJECT_STATUS_DELETED_REMOTE) || t.getObjectStatus().equals(OBJECT_STATUS_SYNCH) )){
			return true;
		}
		return false;
	}
	
	protected String getIdFieldName(){
		Class c = getTypeArgument();
		for (int i = 0; i < c.getDeclaredFields().length; i++) {
			Field f = c.getDeclaredFields()[i];
			Annotation[] annotations = f.getAnnotations();
			if(annotations!=null){
				for (int j = 0; j < annotations.length; j++) {
					Annotation a = annotations[j];
					if(a instanceof LDField){
						if(((LDField) a).id()){
							return f.getName();
						}
					}
				}
			}
		}
		return null;
	}
	
	protected String getTableName(){
		Class c = getTypeArgument();
		String tName = getAnnotatedTableName();
		if(!"".equals(tName))return tName;
		return c.getSimpleName().toUpperCase();
	}
	
	private String getAnnotatedTableName(){
		Class c = getTypeArgument();
		Annotation[] annotations = c.getAnnotations();
		if(annotations!=null){
			for (int i = 0; i < annotations.length; i++) {
				Annotation a = annotations[i];
				if(a instanceof LDEntity){
					return ((LDEntity)a).tableName().toUpperCase();
				}
			}
		}
		return "";
	}
	
	
	private void composeCreateSQL() {
		_sqlCreate = "CREATE TABLE " + getTableName() + " (" + getFieldsString() + ")";
	}
	
	private ContentValues composeContentValues(T t, boolean getIdToo) {
		Class c = getTypeArgument();
		Class superClass = c.getSuperclass();
		
		Field[] fields = c.getDeclaredFields();
		Field[] superFields = superClass.getDeclaredFields();
		Field[] allFields = (Field[]) ArrayUtils.addAll(fields, superFields);
		
		ContentValues contentValues = new ContentValues();
		for (int i = 0; i < allFields.length; i++) {
			Field f = allFields[i];
			Log.d(TAG, f.getName());
			Annotation[] annotations = f.getAnnotations();
			
			if(f.isAnnotationPresent(LDField.class)){
				LDField ldFieldAnnotation = f.getAnnotation(LDField.class);
				if(getIdToo==true || !ldFieldAnnotation.id()){
					Method m;
					try {
						m = c.getMethod("get" +  StringUtils.capitalize(f.getName()));
						
						String value=m.invoke(t).toString();
						if(LDField.SQLITE_TYPE_BOOLEAN.equals(ldFieldAnnotation.sqliteType())){
							if("true".equals(value))value="1";
							if("false".equals(value))value="0";
						}
						
						Log.d(TAG,"\t" + value);
						if(m.invoke(t)!=null)contentValues.put(f.getName(), value);
						else contentValues.put(f.getName(), "");
					}
					catch (NullPointerException e) {
						Log.e(TAG, "Null value for field " + StringUtils.capitalize(f.getName()));
					}
					catch (Exception e) {
						Log.e(TAG, "Error invoking get for field " + StringUtils.capitalize(f.getName()), e);
					}
				}
			}
			
			/*if(annotations!=null){
				for (int j = 0; j < annotations.length; j++) {
					Annotation a = annotations[j];
					if(a instanceof LDField){
						
						if(getIdToo==true || !((LDField) a).id()){
							Method m;
							try {
								m = c.getMethod("get" +  StringUtils.capitalize(f.getName()));
								
								String value=m.invoke(t).toString();
								
								Log.d(TAG,"\t" + value);
								if(m.invoke(t)!=null)contentValues.put(f.getName(), value);
								else contentValues.put(f.getName(), "");
							}
							catch (NullPointerException e) {
								Log.e(TAG, "Null value for field " + StringUtils.capitalize(f.getName()));
							}
							catch (Exception e) {
								Log.e(TAG, "Error invoking get for field " + StringUtils.capitalize(f.getName()), e);
							}
						}
					}
				}
			}*/
		}
		return contentValues;
	}
	
	private String getFieldsString(){
		
		Class c = getTypeArgument();
		Class superClass = c.getSuperclass();
		
		Field[] fields = c.getDeclaredFields();
		Field[] superFields = superClass.getDeclaredFields();
		Field[] allFields = (Field[]) ArrayUtils.addAll(fields, superFields);
		
		String fieldsString ="";
		boolean firstField = true;
		for (int i =0 ; i< allFields.length; i++){
			String sQLFieldDefinition = composeSQLFieldDefinition(allFields[i]);
			if(sQLFieldDefinition!=null && !sQLFieldDefinition.equals("")){
				if(firstField){
					fieldsString = fieldsString + sQLFieldDefinition;
					firstField=false;
				}
				else{
					fieldsString = fieldsString + ", " + sQLFieldDefinition;
				}
			}
		}
		
		return fieldsString;
	}
	
	protected String[] getFieldNames(){
		Class c = getTypeArgument();
		String[] names= new String[c.getFields().length];
		for (int i = 0; i < c.getFields().length; i++) {
			names[i] = c.getFields()[i].getName();
		}
		return names;
	}
	
	private Class<T> getTypeArgument(){
		ParameterizedType parameterizedType = (ParameterizedType) getClass().getGenericSuperclass();
		return (Class)parameterizedType.getActualTypeArguments()[0];
	}
	
	private String composeSQLFieldDefinition(Field f){
		String sQLFieldDefinition ="";
		Annotation[] annotations = f.getAnnotations();
		if(annotations!=null){
			for (int i = 0; i < annotations.length; i++) {
				Annotation a = annotations[i];
				if(a instanceof LDField){
					LDField ldFiledAnnotation = (LDField)a;
					String type = ldFiledAnnotation.sqliteType();
					if(type.equals(LDField.SQLITE_TYPE_DATE))type=LDField.SQLITE_TYPE_INTEGER;
					if(ldFiledAnnotation.id()){
						sQLFieldDefinition = f.getName() + " " + type + "  primary key autoincrement";
					}
					else {
						sQLFieldDefinition = f.getName() + " " + type;
					}
				}
			}
		}
		return sQLFieldDefinition;
	}
	
	protected T getObjectFromCursor(Cursor cur){
		Class c = getTypeArgument();
		Class superClass = c.getSuperclass();
		
		try {
			Object entityInstance = c.newInstance();
			Field[] fields = c.getDeclaredFields();
			Field[] superFields = superClass.getDeclaredFields();
			Field[] allFields = (Field[]) ArrayUtils.addAll(fields, superFields);
			
			for (int i = 0; i < allFields.length; i++) {
				Field f = allFields[i];
				Annotation[] annotations = f.getAnnotations();
				if(annotations!=null){
					for (int j = 0; j < annotations.length; j++) {
						Annotation a = annotations[j];
						if(a instanceof LDField){
							setFieldValueFromCursor(entityInstance, f, cur, ((LDField)a).sqliteType());
							break;
						}
					}
				}
			}
			Log.d(TAG,"Object populated");
			return (T)entityInstance;
		} catch (Exception e) {
			Log.e(TAG,"Error instantiating class", e);
			return null;
		}
	}
	
	private void setFieldValueFromCursor(Object entityInstance, Field field,
			Cursor cur, String sqliteType) {
		Method m;
		try {
			m = entityInstance.getClass().getMethod("set" +  StringUtils.capitalize(field.getName()), field.getType());
			try {
				if(sqliteType.equals(LDField.SQLITE_TYPE_TEXT)){
					m.invoke(entityInstance, cur.getString(cur.getColumnIndex(field.getName())));
				}
				else if(sqliteType.equals(LDField.SQLITE_TYPE_INTEGER)){
					m.invoke(entityInstance, cur.getLong(cur.getColumnIndex(field.getName())));
				}
				else if(sqliteType.equals(LDField.SQLITE_TYPE_REAL)){
					m.invoke(entityInstance, cur.getDouble(cur.getColumnIndex(field.getName())));
				}
				else if(sqliteType.equals(LDField.SQLITE_TYPE_DATE)){
					m.invoke(entityInstance, cur.getLong(cur.getColumnIndex(field.getName())));
				}
				else if(sqliteType.equals(LDField.SQLITE_TYPE_BOOLEAN)){
					Long longValue = cur.getLong(cur.getColumnIndex(field.getName()));
					m.invoke(entityInstance, new Boolean(longValue.intValue()==0?false:true));
				}
			} catch (Exception e) {
				Log.e(TAG, "Invocation exception (" + field.getName() + ")", e);
			}
		} catch (Exception e) {
			Log.e(TAG, "No method found or security exception (" + field.getName() + ")", e);
		}
	}
	

	private String _sqlCreate = "";
	protected String TAG = this.getClass().getName();
	
	public static final String OBJECT_STATUS_NEW ="NEW";
	public static final String OBJECT_STATUS_DIRTY ="DIRTY";
	public static final String OBJECT_STATUS_SYNCH ="SYNCH";
	public static final String OBJECT_STATUS_DELETED_LOCAL ="DELETED_LOCAL";
	public static final String OBJECT_STATUS_DELETED_REMOTE ="DELETED_REMOTE";

}
