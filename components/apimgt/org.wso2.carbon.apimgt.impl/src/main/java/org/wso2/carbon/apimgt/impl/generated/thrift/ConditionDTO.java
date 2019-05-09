/**
 * Autogenerated by Thrift Compiler (0.12.0)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */
package org.wso2.carbon.apimgt.impl.generated.thrift;

@SuppressWarnings({"cast", "rawtypes", "serial", "unchecked", "unused"})
@javax.annotation.Generated(value = "Autogenerated by Thrift Compiler (0.12.0)", date = "2019-04-08")
public class ConditionDTO implements org.apache.thrift.TBase<ConditionDTO, ConditionDTO._Fields>, java.io.Serializable, Cloneable, Comparable<ConditionDTO> {
  private static final org.apache.thrift.protocol.TStruct STRUCT_DESC = new org.apache.thrift.protocol.TStruct("ConditionDTO");

  private static final org.apache.thrift.protocol.TField CONDITION_TYPE_FIELD_DESC = new org.apache.thrift.protocol.TField("conditionType", org.apache.thrift.protocol.TType.STRING, (short)1);
  private static final org.apache.thrift.protocol.TField CONDITION_NAME_FIELD_DESC = new org.apache.thrift.protocol.TField("conditionName", org.apache.thrift.protocol.TType.STRING, (short)2);
  private static final org.apache.thrift.protocol.TField CONDITION_VALUE_FIELD_DESC = new org.apache.thrift.protocol.TField("conditionValue", org.apache.thrift.protocol.TType.STRING, (short)3);
  private static final org.apache.thrift.protocol.TField IS_INVERTED_FIELD_DESC = new org.apache.thrift.protocol.TField("isInverted", org.apache.thrift.protocol.TType.BOOL, (short)4);

  private static final org.apache.thrift.scheme.SchemeFactory STANDARD_SCHEME_FACTORY = new ConditionDTOStandardSchemeFactory();
  private static final org.apache.thrift.scheme.SchemeFactory TUPLE_SCHEME_FACTORY = new ConditionDTOTupleSchemeFactory();

  public @org.apache.thrift.annotation.Nullable String conditionType; // optional
  public @org.apache.thrift.annotation.Nullable String conditionName; // optional
  public @org.apache.thrift.annotation.Nullable String conditionValue; // optional
  public boolean isInverted; // optional

  /** The set of fields this struct contains, along with convenience methods for finding and manipulating them. */
  public enum _Fields implements org.apache.thrift.TFieldIdEnum {
    CONDITION_TYPE((short)1, "conditionType"),
    CONDITION_NAME((short)2, "conditionName"),
    CONDITION_VALUE((short)3, "conditionValue"),
    IS_INVERTED((short)4, "isInverted");

    private static final java.util.Map<String, _Fields> byName = new java.util.HashMap<String, _Fields>();

    static {
      for (_Fields field : java.util.EnumSet.allOf(_Fields.class)) {
        byName.put(field.getFieldName(), field);
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByThriftId(int fieldId) {
      switch(fieldId) {
        case 1: // CONDITION_TYPE
          return CONDITION_TYPE;
        case 2: // CONDITION_NAME
          return CONDITION_NAME;
        case 3: // CONDITION_VALUE
          return CONDITION_VALUE;
        case 4: // IS_INVERTED
          return IS_INVERTED;
        default:
          return null;
      }
    }

    /**
     * Find the _Fields constant that matches fieldId, throwing an exception
     * if it is not found.
     */
    public static _Fields findByThriftIdOrThrow(int fieldId) {
      _Fields fields = findByThriftId(fieldId);
      if (fields == null) throw new IllegalArgumentException("Field " + fieldId + " doesn't exist!");
      return fields;
    }

    /**
     * Find the _Fields constant that matches name, or null if its not found.
     */
    @org.apache.thrift.annotation.Nullable
    public static _Fields findByName(String name) {
      return byName.get(name);
    }

    private final short _thriftId;
    private final String _fieldName;

    _Fields(short thriftId, String fieldName) {
      _thriftId = thriftId;
      _fieldName = fieldName;
    }

    public short getThriftFieldId() {
      return _thriftId;
    }

    public String getFieldName() {
      return _fieldName;
    }
  }

  // isset id assignments
  private static final int __ISINVERTED_ISSET_ID = 0;
  private byte __isset_bitfield = 0;
  private static final _Fields optionals[] = {_Fields.CONDITION_TYPE,_Fields.CONDITION_NAME,_Fields.CONDITION_VALUE,_Fields.IS_INVERTED};
  public static final java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> metaDataMap;
  static {
    java.util.Map<_Fields, org.apache.thrift.meta_data.FieldMetaData> tmpMap = new java.util.EnumMap<_Fields, org.apache.thrift.meta_data.FieldMetaData>(_Fields.class);
    tmpMap.put(_Fields.CONDITION_TYPE, new org.apache.thrift.meta_data.FieldMetaData("conditionType", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.CONDITION_NAME, new org.apache.thrift.meta_data.FieldMetaData("conditionName", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.CONDITION_VALUE, new org.apache.thrift.meta_data.FieldMetaData("conditionValue", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.STRING)));
    tmpMap.put(_Fields.IS_INVERTED, new org.apache.thrift.meta_data.FieldMetaData("isInverted", org.apache.thrift.TFieldRequirementType.OPTIONAL, 
        new org.apache.thrift.meta_data.FieldValueMetaData(org.apache.thrift.protocol.TType.BOOL)));
    metaDataMap = java.util.Collections.unmodifiableMap(tmpMap);
    org.apache.thrift.meta_data.FieldMetaData.addStructMetaDataMap(ConditionDTO.class, metaDataMap);
  }

  public ConditionDTO() {
  }

  /**
   * Performs a deep copy on <i>other</i>.
   */
  public ConditionDTO(ConditionDTO other) {
    __isset_bitfield = other.__isset_bitfield;
    if (other.isSetConditionType()) {
      this.conditionType = other.conditionType;
    }
    if (other.isSetConditionName()) {
      this.conditionName = other.conditionName;
    }
    if (other.isSetConditionValue()) {
      this.conditionValue = other.conditionValue;
    }
    this.isInverted = other.isInverted;
  }

  public ConditionDTO deepCopy() {
    return new ConditionDTO(this);
  }

  @Override
  public void clear() {
    this.conditionType = null;
    this.conditionName = null;
    this.conditionValue = null;
    setIsInvertedIsSet(false);
    this.isInverted = false;
  }

  @org.apache.thrift.annotation.Nullable
  public String getConditionType() {
    return this.conditionType;
  }

  public ConditionDTO setConditionType(@org.apache.thrift.annotation.Nullable String conditionType) {
    this.conditionType = conditionType;
    return this;
  }

  public void unsetConditionType() {
    this.conditionType = null;
  }

  /** Returns true if field conditionType is set (has been assigned a value) and false otherwise */
  public boolean isSetConditionType() {
    return this.conditionType != null;
  }

  public void setConditionTypeIsSet(boolean value) {
    if (!value) {
      this.conditionType = null;
    }
  }

  @org.apache.thrift.annotation.Nullable
  public String getConditionName() {
    return this.conditionName;
  }

  public ConditionDTO setConditionName(@org.apache.thrift.annotation.Nullable String conditionName) {
    this.conditionName = conditionName;
    return this;
  }

  public void unsetConditionName() {
    this.conditionName = null;
  }

  /** Returns true if field conditionName is set (has been assigned a value) and false otherwise */
  public boolean isSetConditionName() {
    return this.conditionName != null;
  }

  public void setConditionNameIsSet(boolean value) {
    if (!value) {
      this.conditionName = null;
    }
  }

  @org.apache.thrift.annotation.Nullable
  public String getConditionValue() {
    return this.conditionValue;
  }

  public ConditionDTO setConditionValue(@org.apache.thrift.annotation.Nullable String conditionValue) {
    this.conditionValue = conditionValue;
    return this;
  }

  public void unsetConditionValue() {
    this.conditionValue = null;
  }

  /** Returns true if field conditionValue is set (has been assigned a value) and false otherwise */
  public boolean isSetConditionValue() {
    return this.conditionValue != null;
  }

  public void setConditionValueIsSet(boolean value) {
    if (!value) {
      this.conditionValue = null;
    }
  }

  public boolean isIsInverted() {
    return this.isInverted;
  }

  public ConditionDTO setIsInverted(boolean isInverted) {
    this.isInverted = isInverted;
    setIsInvertedIsSet(true);
    return this;
  }

  public void unsetIsInverted() {
    __isset_bitfield = org.apache.thrift.EncodingUtils.clearBit(__isset_bitfield, __ISINVERTED_ISSET_ID);
  }

  /** Returns true if field isInverted is set (has been assigned a value) and false otherwise */
  public boolean isSetIsInverted() {
    return org.apache.thrift.EncodingUtils.testBit(__isset_bitfield, __ISINVERTED_ISSET_ID);
  }

  public void setIsInvertedIsSet(boolean value) {
    __isset_bitfield = org.apache.thrift.EncodingUtils.setBit(__isset_bitfield, __ISINVERTED_ISSET_ID, value);
  }

  public void setFieldValue(_Fields field, @org.apache.thrift.annotation.Nullable Object value) {
    switch (field) {
    case CONDITION_TYPE:
      if (value == null) {
        unsetConditionType();
      } else {
        setConditionType((String)value);
      }
      break;

    case CONDITION_NAME:
      if (value == null) {
        unsetConditionName();
      } else {
        setConditionName((String)value);
      }
      break;

    case CONDITION_VALUE:
      if (value == null) {
        unsetConditionValue();
      } else {
        setConditionValue((String)value);
      }
      break;

    case IS_INVERTED:
      if (value == null) {
        unsetIsInverted();
      } else {
        setIsInverted((Boolean)value);
      }
      break;

    }
  }

  @org.apache.thrift.annotation.Nullable
  public Object getFieldValue(_Fields field) {
    switch (field) {
    case CONDITION_TYPE:
      return getConditionType();

    case CONDITION_NAME:
      return getConditionName();

    case CONDITION_VALUE:
      return getConditionValue();

    case IS_INVERTED:
      return isIsInverted();

    }
    throw new IllegalStateException();
  }

  /** Returns true if field corresponding to fieldID is set (has been assigned a value) and false otherwise */
  public boolean isSet(_Fields field) {
    if (field == null) {
      throw new IllegalArgumentException();
    }

    switch (field) {
    case CONDITION_TYPE:
      return isSetConditionType();
    case CONDITION_NAME:
      return isSetConditionName();
    case CONDITION_VALUE:
      return isSetConditionValue();
    case IS_INVERTED:
      return isSetIsInverted();
    }
    throw new IllegalStateException();
  }

  @Override
  public boolean equals(Object that) {
    if (that == null)
      return false;
    if (that instanceof ConditionDTO)
      return this.equals((ConditionDTO)that);
    return false;
  }

  public boolean equals(ConditionDTO that) {
    if (that == null)
      return false;
    if (this == that)
      return true;

    boolean this_present_conditionType = true && this.isSetConditionType();
    boolean that_present_conditionType = true && that.isSetConditionType();
    if (this_present_conditionType || that_present_conditionType) {
      if (!(this_present_conditionType && that_present_conditionType))
        return false;
      if (!this.conditionType.equals(that.conditionType))
        return false;
    }

    boolean this_present_conditionName = true && this.isSetConditionName();
    boolean that_present_conditionName = true && that.isSetConditionName();
    if (this_present_conditionName || that_present_conditionName) {
      if (!(this_present_conditionName && that_present_conditionName))
        return false;
      if (!this.conditionName.equals(that.conditionName))
        return false;
    }

    boolean this_present_conditionValue = true && this.isSetConditionValue();
    boolean that_present_conditionValue = true && that.isSetConditionValue();
    if (this_present_conditionValue || that_present_conditionValue) {
      if (!(this_present_conditionValue && that_present_conditionValue))
        return false;
      if (!this.conditionValue.equals(that.conditionValue))
        return false;
    }

    boolean this_present_isInverted = true && this.isSetIsInverted();
    boolean that_present_isInverted = true && that.isSetIsInverted();
    if (this_present_isInverted || that_present_isInverted) {
      if (!(this_present_isInverted && that_present_isInverted))
        return false;
      if (this.isInverted != that.isInverted)
        return false;
    }

    return true;
  }

  @Override
  public int hashCode() {
    int hashCode = 1;

    hashCode = hashCode * 8191 + ((isSetConditionType()) ? 131071 : 524287);
    if (isSetConditionType())
      hashCode = hashCode * 8191 + conditionType.hashCode();

    hashCode = hashCode * 8191 + ((isSetConditionName()) ? 131071 : 524287);
    if (isSetConditionName())
      hashCode = hashCode * 8191 + conditionName.hashCode();

    hashCode = hashCode * 8191 + ((isSetConditionValue()) ? 131071 : 524287);
    if (isSetConditionValue())
      hashCode = hashCode * 8191 + conditionValue.hashCode();

    hashCode = hashCode * 8191 + ((isSetIsInverted()) ? 131071 : 524287);
    if (isSetIsInverted())
      hashCode = hashCode * 8191 + ((isInverted) ? 131071 : 524287);

    return hashCode;
  }

  @Override
  public int compareTo(ConditionDTO other) {
    if (!getClass().equals(other.getClass())) {
      return getClass().getName().compareTo(other.getClass().getName());
    }

    int lastComparison = 0;

    lastComparison = Boolean.valueOf(isSetConditionType()).compareTo(other.isSetConditionType());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetConditionType()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.conditionType, other.conditionType);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetConditionName()).compareTo(other.isSetConditionName());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetConditionName()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.conditionName, other.conditionName);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetConditionValue()).compareTo(other.isSetConditionValue());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetConditionValue()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.conditionValue, other.conditionValue);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    lastComparison = Boolean.valueOf(isSetIsInverted()).compareTo(other.isSetIsInverted());
    if (lastComparison != 0) {
      return lastComparison;
    }
    if (isSetIsInverted()) {
      lastComparison = org.apache.thrift.TBaseHelper.compareTo(this.isInverted, other.isInverted);
      if (lastComparison != 0) {
        return lastComparison;
      }
    }
    return 0;
  }

  @org.apache.thrift.annotation.Nullable
  public _Fields fieldForId(int fieldId) {
    return _Fields.findByThriftId(fieldId);
  }

  public void read(org.apache.thrift.protocol.TProtocol iprot) throws org.apache.thrift.TException {
    scheme(iprot).read(iprot, this);
  }

  public void write(org.apache.thrift.protocol.TProtocol oprot) throws org.apache.thrift.TException {
    scheme(oprot).write(oprot, this);
  }

  @Override
  public String toString() {
    StringBuilder sb = new StringBuilder("ConditionDTO(");
    boolean first = true;

    if (isSetConditionType()) {
      sb.append("conditionType:");
      if (this.conditionType == null) {
        sb.append("null");
      } else {
        sb.append(this.conditionType);
      }
      first = false;
    }
    if (isSetConditionName()) {
      if (!first) sb.append(", ");
      sb.append("conditionName:");
      if (this.conditionName == null) {
        sb.append("null");
      } else {
        sb.append(this.conditionName);
      }
      first = false;
    }
    if (isSetConditionValue()) {
      if (!first) sb.append(", ");
      sb.append("conditionValue:");
      if (this.conditionValue == null) {
        sb.append("null");
      } else {
        sb.append(this.conditionValue);
      }
      first = false;
    }
    if (isSetIsInverted()) {
      if (!first) sb.append(", ");
      sb.append("isInverted:");
      sb.append(this.isInverted);
      first = false;
    }
    sb.append(")");
    return sb.toString();
  }

  public void validate() throws org.apache.thrift.TException {
    // check for required fields
    // check for sub-struct validity
  }

  private void writeObject(java.io.ObjectOutputStream out) throws java.io.IOException {
    try {
      write(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(out)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private void readObject(java.io.ObjectInputStream in) throws java.io.IOException, ClassNotFoundException {
    try {
      // it doesn't seem like you should have to do this, but java serialization is wacky, and doesn't call the default constructor.
      __isset_bitfield = 0;
      read(new org.apache.thrift.protocol.TCompactProtocol(new org.apache.thrift.transport.TIOStreamTransport(in)));
    } catch (org.apache.thrift.TException te) {
      throw new java.io.IOException(te);
    }
  }

  private static class ConditionDTOStandardSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public ConditionDTOStandardScheme getScheme() {
      return new ConditionDTOStandardScheme();
    }
  }

  private static class ConditionDTOStandardScheme extends org.apache.thrift.scheme.StandardScheme<ConditionDTO> {

    public void read(org.apache.thrift.protocol.TProtocol iprot, ConditionDTO struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TField schemeField;
      iprot.readStructBegin();
      while (true)
      {
        schemeField = iprot.readFieldBegin();
        if (schemeField.type == org.apache.thrift.protocol.TType.STOP) { 
          break;
        }
        switch (schemeField.id) {
          case 1: // CONDITION_TYPE
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.conditionType = iprot.readString();
              struct.setConditionTypeIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 2: // CONDITION_NAME
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.conditionName = iprot.readString();
              struct.setConditionNameIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 3: // CONDITION_VALUE
            if (schemeField.type == org.apache.thrift.protocol.TType.STRING) {
              struct.conditionValue = iprot.readString();
              struct.setConditionValueIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          case 4: // IS_INVERTED
            if (schemeField.type == org.apache.thrift.protocol.TType.BOOL) {
              struct.isInverted = iprot.readBool();
              struct.setIsInvertedIsSet(true);
            } else { 
              org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
            }
            break;
          default:
            org.apache.thrift.protocol.TProtocolUtil.skip(iprot, schemeField.type);
        }
        iprot.readFieldEnd();
      }
      iprot.readStructEnd();

      // check for required fields of primitive type, which can't be checked in the validate method
      struct.validate();
    }

    public void write(org.apache.thrift.protocol.TProtocol oprot, ConditionDTO struct) throws org.apache.thrift.TException {
      struct.validate();

      oprot.writeStructBegin(STRUCT_DESC);
      if (struct.conditionType != null) {
        if (struct.isSetConditionType()) {
          oprot.writeFieldBegin(CONDITION_TYPE_FIELD_DESC);
          oprot.writeString(struct.conditionType);
          oprot.writeFieldEnd();
        }
      }
      if (struct.conditionName != null) {
        if (struct.isSetConditionName()) {
          oprot.writeFieldBegin(CONDITION_NAME_FIELD_DESC);
          oprot.writeString(struct.conditionName);
          oprot.writeFieldEnd();
        }
      }
      if (struct.conditionValue != null) {
        if (struct.isSetConditionValue()) {
          oprot.writeFieldBegin(CONDITION_VALUE_FIELD_DESC);
          oprot.writeString(struct.conditionValue);
          oprot.writeFieldEnd();
        }
      }
      if (struct.isSetIsInverted()) {
        oprot.writeFieldBegin(IS_INVERTED_FIELD_DESC);
        oprot.writeBool(struct.isInverted);
        oprot.writeFieldEnd();
      }
      oprot.writeFieldStop();
      oprot.writeStructEnd();
    }

  }

  private static class ConditionDTOTupleSchemeFactory implements org.apache.thrift.scheme.SchemeFactory {
    public ConditionDTOTupleScheme getScheme() {
      return new ConditionDTOTupleScheme();
    }
  }

  private static class ConditionDTOTupleScheme extends org.apache.thrift.scheme.TupleScheme<ConditionDTO> {

    @Override
    public void write(org.apache.thrift.protocol.TProtocol prot, ConditionDTO struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol oprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet optionals = new java.util.BitSet();
      if (struct.isSetConditionType()) {
        optionals.set(0);
      }
      if (struct.isSetConditionName()) {
        optionals.set(1);
      }
      if (struct.isSetConditionValue()) {
        optionals.set(2);
      }
      if (struct.isSetIsInverted()) {
        optionals.set(3);
      }
      oprot.writeBitSet(optionals, 4);
      if (struct.isSetConditionType()) {
        oprot.writeString(struct.conditionType);
      }
      if (struct.isSetConditionName()) {
        oprot.writeString(struct.conditionName);
      }
      if (struct.isSetConditionValue()) {
        oprot.writeString(struct.conditionValue);
      }
      if (struct.isSetIsInverted()) {
        oprot.writeBool(struct.isInverted);
      }
    }

    @Override
    public void read(org.apache.thrift.protocol.TProtocol prot, ConditionDTO struct) throws org.apache.thrift.TException {
      org.apache.thrift.protocol.TTupleProtocol iprot = (org.apache.thrift.protocol.TTupleProtocol) prot;
      java.util.BitSet incoming = iprot.readBitSet(4);
      if (incoming.get(0)) {
        struct.conditionType = iprot.readString();
        struct.setConditionTypeIsSet(true);
      }
      if (incoming.get(1)) {
        struct.conditionName = iprot.readString();
        struct.setConditionNameIsSet(true);
      }
      if (incoming.get(2)) {
        struct.conditionValue = iprot.readString();
        struct.setConditionValueIsSet(true);
      }
      if (incoming.get(3)) {
        struct.isInverted = iprot.readBool();
        struct.setIsInvertedIsSet(true);
      }
    }
  }

  private static <S extends org.apache.thrift.scheme.IScheme> S scheme(org.apache.thrift.protocol.TProtocol proto) {
    return (org.apache.thrift.scheme.StandardScheme.class.equals(proto.getScheme()) ? STANDARD_SCHEME_FACTORY : TUPLE_SCHEME_FACTORY).getScheme();
  }
}
