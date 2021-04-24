package org.rmj.engr.purchasing.agent;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import net.sf.jasperreports.engine.JRException;
import net.sf.jasperreports.engine.JasperFillManager;
import net.sf.jasperreports.engine.JasperPrint;
import net.sf.jasperreports.engine.data.JsonDataSource;
import net.sf.jasperreports.view.JasperViewer;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.rmj.appdriver.constants.EditMode;
import org.rmj.appdriver.GRider;
import org.rmj.appdriver.MiscUtil;
import org.rmj.appdriver.SQLUtil;
import org.rmj.appdriver.iface.XMRecord;
import org.rmj.appdriver.agentfx.ui.showFXDialog;
import org.rmj.appdriver.constants.RecordStatus;
import org.rmj.engr.client.base.XMClient;
import org.rmj.engr.inventory.base.Inventory;
import org.rmj.engr.parameter.agent.XMBranch;
import org.rmj.engr.parameter.agent.XMDepartment;
import org.rmj.engr.parameter.agent.XMInventoryType;
import org.rmj.engr.parameter.agent.XMSupplier;
import org.rmj.engr.purchasing.base.POReturn;
import org.engr.purchasing.pojo.UnitPOReturnDetail;
import org.engr.purchasing.pojo.UnitPOReturnMaster;
import org.rmj.appdriver.agentfx.callback.IMasterDetail;
import org.rmj.engr.parameter.agent.XMProject;

public class XMPOReturn implements XMRecord{
    public XMPOReturn(GRider foGRider, String fsBranchCD, boolean fbWithParent){
        this.poGRider = foGRider;
        if (foGRider != null){
            this.pbWithParent = fbWithParent;
            this.psBranchCd = fsBranchCD;
            
            poControl = new POReturn();
            poControl.setGRider(foGRider);
            poControl.setBranch(fsBranchCD);
            poControl.setWithParent(fbWithParent);
            
            pnEditMode = EditMode.UNKNOWN;
        }
    }
    
    @Override
    public void setMaster(int fnCol, Object foData) {
        if (pnEditMode != EditMode.UNKNOWN){
            // Don't allow specific fields to assign values
            if(!(fnCol == poData.getColumn("sTransNox") ||
                fnCol == poData.getColumn("cTranStat") ||
                fnCol == poData.getColumn("sModified") ||
                fnCol == poData.getColumn("dModified"))){
                
                poData.setValue(fnCol, foData);
                MasterRetreived(fnCol);
                
                if (fnCol == poData.getColumn("nDiscount") ||
                    fnCol == poData.getColumn("nAddDiscx") ||
                    fnCol == poData.getColumn("nFreightx")){
                    poData.setTranTotal(computeTotal());
                    poData.setTaxWHeld(computeTaxWHeld());
                    MasterRetreived(6);
                    MasterRetreived(7);
                }
            }
        }
    }

    @Override
    public void setMaster(String fsCol, Object foData) {
        setMaster(poData.getColumn(fsCol), foData);
    }

    @Override
    public Object getMaster(int fnCol) {
        if(pnEditMode == EditMode.UNKNOWN || poControl == null)
         return null;
      else{
         return poData.getValue(fnCol);
      }
    }

    @Override
    public Object getMaster(String fsCol) {
        return getMaster(poData.getColumn(fsCol));
    }

    @Override
    public boolean newRecord() {
        poData = poControl.newTransaction();              
        
        if (poData == null){
            showMessage();
            return false;
        }else{
            poData.setDateTransact(poGRider.getSysDate());
            poData.setVATRate(pxeTaxRate);
            
            addDetail();
            pnEditMode = EditMode.ADDNEW;
            return true;
        }
    }

    @Override
    public boolean openRecord(String fstransNox) {
        poData = poControl.loadTransaction(fstransNox);
        
        if (poData.getTransNox()== null){
            showMessage();
            return false;
        } else{
            pnEditMode = EditMode.READY;
            return true;
        }
    }

    @Override
    public boolean updateRecord() {
        if(pnEditMode != EditMode.READY) {
         return false;
      }
      else{
         pnEditMode = EditMode.UPDATE;
         return true;
      }
    }

    @Override
    public boolean saveRecord() {
        if(pnEditMode == EditMode.UNKNOWN){
            return false;
        }else{
            poData.setTranTotal(computeTotal());
            poData.setTaxWHeld(computeTaxWHeld());
            // Perform testing on values that needs approval here...
            UnitPOReturnMaster loResult;
            if(pnEditMode == EditMode.ADDNEW)
                loResult = poControl.saveUpdate(poData, "");
            else loResult = poControl.saveUpdate(poData, (String) poData.getValue(1));

            if(loResult == null){
                showMessage();
                return false;
            }else{
                pnEditMode = EditMode.READY;
                poData = loResult;
                return true;
            }
      }
    }

    @Override
    public boolean deleteRecord(String fsTransNox) {
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.deleteTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else showMessage();

            return lbResult;
        }
    }

    @Override
    public boolean deactivateRecord(String fsTransNox) {
        return false;
    }

    @Override
    public boolean activateRecord(String fsTransNox) {
        return false;
    }

    @Override
    public void setBranch(String foBranchCD) {
        psBranchCd = foBranchCD;
    }

    @Override
    public int getEditMode() {
        return pnEditMode;
    }
    
    //Added methods
    public void setGRider(GRider foGrider){
        this.poGRider = foGrider;
        this.psUserIDxx = foGrider.getUserID();
        
        if (psBranchCd.isEmpty()) psBranchCd = poGRider.getBranchCode();
    }
    
    public boolean deleteDetail(int fnRow){
        boolean lbDel = poControl.deleteDetail(fnRow);
        
        if (lbDel){
            poData.setTranTotal(computeTotal());
            poData.setTaxWHeld(computeTaxWHeld());
            return true;
        } else return false;
    }
    
    public boolean cancelRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.cancelTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else showMessage();

            return lbResult;
        }
    }
    
    public boolean closeRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.closeTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else showMessage();

            return lbResult;
        }
    }
    
    public boolean postRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.postTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else showMessage();

            return lbResult;
        }
    }
    
    public boolean voidRecord(String fsTransNox){
        if(pnEditMode != EditMode.READY){
            return false;
        } else{
            boolean lbResult = poControl.voidTransaction(fsTransNox);
            if (lbResult)
                pnEditMode = EditMode.UNKNOWN;
            else showMessage();

            return lbResult;
        }
    }
    
    public boolean addDetail(){return poControl.addDetail();}
    public int getDetailCount(){return poControl.ItemCount();}
    
    public void setDetail(int fnRow, int fnCol, Object foData){
        if (pnEditMode != EditMode.UNKNOWN){
            // Don't allow specific fields to assign values
            if(!(fnCol == poDetail.getColumn("sTransNox") ||
                fnCol == poDetail.getColumn("nEntryNox") ||
                fnCol == poDetail.getColumn("dModified"))){
                
                poControl.setDetail(fnRow, fnCol, foData);
                DetailRetreived(fnCol);
                
                if (fnCol == poDetail.getColumn("nQuantity") ||
                    fnCol == poDetail.getColumn("nUnitPrce") ||
                    fnCol == poDetail.getColumn("nFreightx")){
                    poData.setTranTotal(computeTotal());
                    poData.setTaxWHeld(computeTaxWHeld());
                    MasterRetreived(6);
                    MasterRetreived(7);
                }
            }
        }
    }

    public void setDetail(int fnRow, String fsCol, Object foData){        
        setDetail(fnRow, poDetail.getColumn(fsCol), foData);
    }
    
    public Object getDetail(int fnRow, String fsCol){return poControl.getDetail(fnRow, fsCol);}
    public Object getDetail(int fnRow, int fnCol){return poControl.getDetail(fnRow, fnCol);}

    private double computeTotal(){
        double lnTranTotal = 0;
        for (int lnCtr = 0; lnCtr <= poControl.ItemCount()-1; lnCtr ++){
            lnTranTotal += ((int) poControl.getDetail(lnCtr, "nQuantity") * (Double) poControl.getDetail(lnCtr, "nUnitPrce")) 
                                + (Double) poControl.getDetail(lnCtr, "nFreightx");
        }
        
        //add the freight charge to total order
        lnTranTotal += (Double) poData.getFreightCharge();
        //less the discounts
        lnTranTotal = lnTranTotal - (lnTranTotal * (Double) poData.getDiscountRate()) - (Double) poData.getAdditionalDisc();
        return lnTranTotal;
    }
    
    private double computeTaxWHeld(){
        //DecimalFormat df2 = new DecimalFormat(".##");
        //String lsTaxWHeld = df2.format(((Double) poData.getTranTotal() / pxeTaxExcludRte) * pxeTaxWHeldRate);
        //return Double.parseDouble(lsTaxWHeld);        
        
        return 0.00;
    }
    
    private void showMessage(){
        psMessage = "";
        psErrMesg = "";
        if (!poControl.getErrMsg().isEmpty()){
            if (!poControl.getMessage().isEmpty()){
                psMessage = poControl.getErrMsg();
                psErrMesg = poControl.getMessage();
            } else 
                psErrMesg = poControl.getErrMsg();
        }else 
            psMessage = poControl.getMessage();
    }
    
    public boolean BrowseRecord(String fsValue, boolean fbByCode){
        String lsHeader = "Inv. Type»Supplier»Date»Inv. Type»Date";
        String lsColName = "sInvTypCd»sClientNm»dTransact»sDescript»dTransact";
        String lsColCrit = "a.sInvTypCd»d.sClientNm»a.dTransact»c.sDescript»a.dTransact";
        String lsSQL = getSQ_POReturn();
       
        JSONObject loJSON = showFXDialog.jsonSearch(poGRider, 
                                                    lsSQL, 
                                                    fsValue, 
                                                    lsHeader, 
                                                    lsColName, 
                                                    lsColCrit, 
                                                    fbByCode ? 0 : 1);
        
        if(loJSON == null)
            return false;
        else{
            return openRecord((String) loJSON.get("sTransNox"));
        } 
    }
    
    public Inventory GetInventory(String fsValue, boolean fbByCode, boolean fbSearch){        
        Inventory instance = new Inventory(poGRider, psBranchCd, fbSearch);
        instance.BrowseRecord(fsValue, fbByCode, false);
        return instance;
    }
    
    public XMPOReceiving GetPOReceving(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMPOReceiving instance  = new XMPOReceiving(poGRider, psBranchCd, true);
        instance.setTranStat(1);
        if (instance.BrowseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public XMProject GetProject(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMProject instance  = new XMProject(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public XMBranch GetBranch(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMBranch instance  = new XMBranch(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public JSONObject GetSupplier(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMClient instance  = new XMClient(poGRider, psBranchCd, true);
        return instance.SearchClient(fsValue, fbByCode);
    }
    
    public XMInventoryType GetInventoryType(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMInventoryType instance  = new XMInventoryType(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public XMDepartment GetDepartment(String fsValue, boolean fbByCode){
        if (fbByCode && fsValue.equals("")) return null;
        
        XMDepartment instance  = new XMDepartment(poGRider, psBranchCd, true);
        if (instance.browseRecord(fsValue, fbByCode))
            return instance;
        else
            return null;
    }
    
    public boolean SearchMaster(int fnCol, String fsValue, boolean fbByCode){
        boolean lbReturn = false;
        
        switch(fnCol){
            case 2: //sBranchCd
                XMProject loProject = new XMProject(poGRider, psBranchCd, true);
                if (loProject.browseRecord(fsValue, fbByCode)){
                    setMaster(fnCol, loProject.getMaster("sProjCode"));
                    //setMaster("sCompnyID", loProject.getMaster("sCompnyID"));
                    MasterRetreived(fnCol);
                    return true;
                }
                break;
            case 5: //sSupplier
                 XMSupplier loSupplier = new XMSupplier(poGRider, psBranchCd, true);
                if (loSupplier.browseRecord(fsValue, psBranchCd, fbByCode)){
                    setMaster(fnCol, loSupplier.getMaster("sClientID"));
                    MasterRetreived(fnCol);
                    lbReturn = true;
                }
                break;
            case 16: //PO Receiving Trans
                XMPOReceiving loPORec = new XMPOReceiving(poGRider, psBranchCd, true);
                loPORec.setTranStat(1);
                
                if (loPORec.BrowseRecord(fsValue, false)){
                    setMaster(fnCol, loPORec.getMaster("sTransNox"));
                    setMaster("sSourceCd", loPORec.getSourceCd());
                    setMaster("sBranchCd", loPORec.getMaster("sBranchCd"));
                    setMaster("sDeptIDxx", loPORec.getMaster("sDeptIDxx"));
                    setMaster("cDivision", loPORec.getMaster("cDivision"));
                    setMaster("sCompnyID", loPORec.getMaster("sCompnyID"));
                    setMaster("sInvTypCd", loPORec.getMaster("sInvTypCd"));
                    setMaster("sSupplier", loPORec.getMaster("sSupplier"));                    
                    lbReturn = true;
                } else {
                    setMaster(fnCol, "");
                    setMaster("sSourceCd", "");
                    setMaster("sBranchCd", "");
                    setMaster("sDeptIDxx", "");
                    setMaster("cDivision", "");
                    setMaster("sCompnyID", "");
                    setMaster("sInvTypCd", "");
                    setMaster("sSupplier", "");
                    
                }
                
                MasterRetreived(fnCol);
                MasterRetreived(2);
                MasterRetreived(5);
                MasterRetreived(18);
                MasterRetreived(27);
                MasterRetreived(28);
                return lbReturn;
            case 18: //sInvTypCd
                XMInventoryType loInv = new XMInventoryType(poGRider, psBranchCd, true);
                if (loInv.browseRecord(fsValue, fbByCode)){
                    setMaster(fnCol, loInv.getMaster("sInvTypCd"));
                    lbReturn = true;
                } else {
                    setMaster(fnCol, "");
                }
                
                MasterRetreived(fnCol);
                return lbReturn;
            case 27:
                XMDepartment loDept = new XMDepartment(poGRider, psBranchCd, true);
                if (loDept.browseRecord(fsValue, fbByCode)){
                    setMaster(fnCol, loDept.getMaster("sDeptIDxx"));
                    lbReturn = true;
                } else {
                    setMaster(fnCol, "");
                }
                
                MasterRetreived(fnCol);
                return lbReturn;
        }
        
        return false;
    }
    
    public boolean SearchMaster(String fsCol, String fsValue, boolean fbByCode){
        return SearchMaster(poData.getColumn(fsCol), fsValue, fbByCode);
    }
    
    public JSONObject SearchDetail(int fnRow, int fnCol, String fsValue, boolean fbSearch, boolean fbByCode){
        String lsHeader = "";
        String lsColName = "";
        String lsColCrit = "";
        String lsSQL = "";
        JSONObject loJSON;
        ResultSet loRS;
        switch(fnCol){
            case 3:
                if (poData.getBranchCd().isEmpty()){
                    psMessage = "Please select a PROJECT NAME first.";
                    return null;
                }
                
                lsHeader = "Brand»Description»Unit»Model»Inv. Type»Barcode»Stock ID";
                lsColName = "xBrandNme»sDescript»sMeasurNm»xModelNme»xInvTypNm»sBarCodex»sStockIDx";
                lsColCrit = "b.sDescript»a.sDescript»f.sMeasurNm»c.sDescript»d.sDescript»a.sBarCodex»a.sStockIDx";
                
                if (getMaster("sPOTransx").equals(""))
                    lsSQL = MiscUtil.addCondition(getSQ_Stocks(""), 
                                "a.cRecdStat = " + SQLUtil.toSQL(RecordStatus.ACTIVE) + 
                                    " AND e.sBranchCd = " + SQLUtil.toSQL(poData.getBranchCd()));
                else 
                    lsSQL = MiscUtil.addCondition(getSQ_Stocks((String) getMaster("sPOTransx")), 
                                "a.cRecdStat = " + SQLUtil.toSQL(RecordStatus.ACTIVE) + 
                                    " AND e.sBranchCd = " + SQLUtil.toSQL(poData.getBranchCd()));
                
                if (fbByCode){
                    lsSQL = MiscUtil.addCondition(lsSQL, "a.sStockIDx = " + SQLUtil.toSQL(fsValue));
                    
                    loRS = poGRider.executeQuery(lsSQL);
                    
                    loJSON = showFXDialog.jsonBrowse(poGRider, loRS, lsHeader, lsColName);
                }else {
                    loJSON = showFXDialog.jsonSearch(poGRider, 
                                                        lsSQL, 
                                                        fsValue, 
                                                        lsHeader, 
                                                        lsColName, 
                                                        lsColCrit, 
                                                        fbSearch ? 1 : 5);
                }
                                
                if (loJSON != null){
                    setDetail(fnRow, fnCol, (String) loJSON.get("sStockIDx"));     
                    if (!getMaster("sPOTransx").equals("")){
                        setDetail(fnRow, "nQuantity", Integer.parseInt((String) loJSON.get("nQuantity")));     
                        setDetail(fnRow, "nUnitPrce", Double.parseDouble((String) loJSON.get("nUnitPrce")));     
                    } else {
                        setDetail(fnRow, "nQuantity", 0);     
                        setDetail(fnRow, "nUnitPrce", 0.00);     
                    }
                    
                    return loJSON;
                } else{
                    setDetail(fnRow, fnCol, "");
                    setDetail(fnRow, "nQuantity", 0);     
                    setDetail(fnRow, "nUnitPrce", 0.00);      
                    
                    psMessage = "No record selected.";
                    return null;
                }
            default:
                return null;
        }
    }
    
    public JSONObject SearchDetail(int fnRow, String fsCol, String fsValue, boolean fbSearch, boolean fbByCode){
        return SearchDetail(fnRow, poDetail.getColumn(fsCol), fsValue, fbSearch, fbByCode);
    }
    
    public boolean printRecord(){
        if (pnEditMode != EditMode.READY || poData == null){
            psErrMesg = "Unable to print transaction.";
            return false;
        }
        
        String lsSupplier = "";
        
        ResultSet loRS = poGRider.executeQuery("SELECT sClientNm FROM Client_Master WHERE sClientID = " + SQLUtil.toSQL(poData.getSupplier()));
        
        try {
            if (loRS.next()) lsSupplier = loRS.getString("sClientNm");
        } catch (SQLException ex) {
            Logger.getLogger(XMPOReturn.class.getName()).log(Level.SEVERE, null, ex);
        }
        
        //Create the parameter
        Map<String, Object> params = new HashMap<>();
        params.put("sCompnyNm", "Guanzon Group");
        params.put("sBranchNm", poGRider.getBranchName());
        params.put("sAddressx", poGRider.getAddress() + ", " + poGRider.getTownName() + " " +poGRider.getProvince());
        params.put("sTransNox", poData.getTransNox());
        params.put("sReferNox", poData.getPOTrans());
        params.put("sSupplier", lsSupplier);
        params.put("dTransact", SQLUtil.dateFormat(poData.getDateTransact(), SQLUtil.FORMAT_LONG_DATE));
        params.put("sPrintdBy", poGRider.getUserID());
        
        JSONObject loJSON;
        JSONArray loArray = new JSONArray();
        
        String lsBarCodex;
        String lsDescript;
        Inventory loInventory = new Inventory(poGRider, psBranchCd, true);
        
        for (int lnCtr = 0; lnCtr <= poControl.ItemCount() -1; lnCtr ++){
            loInventory.BrowseRecord((String) poControl.getDetail(lnCtr, "sStockIDx"), true, false);
            lsBarCodex = (String) loInventory.getMaster("sBarCodex");
            lsDescript = (String) loInventory.getMaster("sDescript");
            
            loJSON = new JSONObject();
            loJSON.put("sField01", lsBarCodex);
            loJSON.put("sField02", lsDescript);
            loJSON.put("nField01", poControl.getDetail(lnCtr, "nQuantity"));
            loJSON.put("lField01", poControl.getDetail(lnCtr, "nUnitPrce"));
            loArray.add(loJSON);
        }
                 
        try {
            InputStream stream = new ByteArrayInputStream(loArray.toJSONString().getBytes("UTF-8"));
            JsonDataSource jrjson;
            
            jrjson = new JsonDataSource(stream);
            
            JasperPrint jrprint = JasperFillManager.fillReport(poGRider.getReportPath() + 
                                                                "PurchaseReturn.jasper", params, jrjson);
        
            JasperViewer jv = new JasperViewer(jrprint, false);     
            jv.setVisible(true);
        } catch (JRException | UnsupportedEncodingException ex) {
            Logger.getLogger(XMPOReceiving.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }

        return true;
    }
    
    private String getSQ_Stocks(String fsPOTransx){
        if (fsPOTransx.isEmpty())
            return "SELECT " +
                        "  a.sStockIDx" +
                        ", a.sBarCodex" + 
                        ", a.sDescript" + 
                        ", a.sBriefDsc" + 
                        ", a.sAltBarCd" + 
                        ", a.sCategCd1" + 
                        ", a.sCategCd2" + 
                        ", a.sCategCd3" + 
                        ", a.sCategCd4" + 
                        ", a.sBrandCde" + 
                        ", a.sModelCde" + 
                        ", a.sColorCde" + 
                        ", a.sInvTypCd" + 
                        ", a.nUnitPrce" + 
                        ", a.nSelPrice" + 
                        ", a.nDiscLev1" + 
                        ", a.nDiscLev2" + 
                        ", a.nDiscLev3" + 
                        ", a.nDealrDsc" + 
                        ", a.cComboInv" + 
                        ", a.cWthPromo" + 
                        ", a.cSerialze" + 
                        ", a.cUnitType" + 
                        ", a.cInvStatx" + 
                        ", a.sSupersed" + 
                        ", a.cRecdStat" + 
                        ", b.sDescript xBrandNme" + 
                        ", c.sDescript xModelNme" + 
                        ", d.sDescript xInvTypNm" + 
                        ", f.sMeasurNm" +
                    " FROM Inventory a" + 
                            " LEFT JOIN Brand b" + 
                                " ON a.sBrandCde = b.sBrandCde" + 
                            " LEFT JOIN Model c" + 
                                " ON a.sModelCde = c.sModelCde" + 
                            " LEFT JOIN Inv_Type d" + 
                                " ON a.sInvTypCd = d.sInvTypCd" + 
                            " LEFT JOIN Measure f" +
                                " ON a.sMeasurID = f.sMeasurID" +
                        ", Inv_Master e" + 
                    " WHERE a.sStockIDx = e.sStockIDx";
        else 
            return "SELECT " +
                        "  a.sStockIDx" +
                        ", a.sBarCodex" + 
                        ", a.sDescript" + 
                        ", a.sBriefDsc" + 
                        ", a.sAltBarCd" + 
                        ", a.sCategCd1" + 
                        ", a.sCategCd2" + 
                        ", a.sCategCd3" + 
                        ", a.sCategCd4" + 
                        ", a.sBrandCde" + 
                        ", a.sModelCde" + 
                        ", a.sColorCde" + 
                        ", a.sInvTypCd" + 
                        ", a.nUnitPrce" + 
                        ", a.nSelPrice" + 
                        ", a.nDiscLev1" + 
                        ", a.nDiscLev2" + 
                        ", a.nDiscLev3" + 
                        ", a.nDealrDsc" + 
                        ", a.cComboInv" + 
                        ", a.cWthPromo" + 
                        ", a.cSerialze" + 
                        ", g.cUnitType" + 
                        ", a.cInvStatx" + 
                        ", a.sSupersed" + 
                        ", a.cRecdStat" + 
                        ", b.sDescript xBrandNme" + 
                        ", c.sDescript xModelNme" + 
                        ", d.sDescript xInvTypNm" + 
                        ", f.sMeasurNm" +
                        ", g.nQuantity" +
                        ", g.nUnitPrce" +
                        ", g.nFreightx" +
                    " FROM Inventory a" + 
                            " LEFT JOIN Brand b" + 
                                " ON a.sBrandCde = b.sBrandCde" + 
                            " LEFT JOIN Model c" + 
                                " ON a.sModelCde = c.sModelCde" + 
                            " LEFT JOIN Inv_Type d" + 
                                " ON a.sInvTypCd = d.sInvTypCd" + 
                            " LEFT JOIN Measure f" +
                                " ON a.sMeasurID = f.sMeasurID" +
                        ", Inv_Master e" + 
                        ", PO_Receiving_Detail g" +
                        ", PO_Receiving_Master h" +
                    " WHERE a.sStockIDx = e.sStockIDx" + 
                        " AND g.sTransNox = h.sTransNox" +
                        " AND e.sStockIDx = g.sStockIDx" +
                        " AND e.nQtyOnHnd > 0" +
                        " AND g.sTransNox = " + SQLUtil.toSQL(fsPOTransx);
    }
    
    private String getSQ_ProcessedReceiving(){
        return "SELECT " +
                    "  a.sTransNox" +
                    ", a.sBranchCd" + 
                    ", a.dTransact" +
                    ", a.sInvTypCd" +
                    ", a.nTranTotl" + 
                    ", b.sBranchNm" + 
                    ", c.sDescript" + 
                    ", d.sClientNm" + 
                    ", a.cTranStat" + 
                    ", a.dRefernce" +
                    ", a.sReferNox" + 
                    ", CASE " +
                        " WHEN a.cTranStat = '0' THEN 'OPEN'" +
                        " WHEN a.cTranStat = '1' THEN 'CLOSED'" +
                        " WHEN a.cTranStat = '2' THEN 'POSTED'" +
                        " WHEN a.cTranStat = '3' THEN 'CANCELLED'" +
                        " WHEN a.cTranStat = '4' THEN 'VOID'" +
                        " END AS xTranStat" +
                    ", a.sBranchCd" + 
                    ", a.sDeptIDxx" + 
                    ", a.cDivision" + 
                    ", a.sCompnyID" +
                    ", a.sSupplier" +
                " FROM PO_Receiving_Master a" + 
                            " LEFT JOIN Branch b" + 
                                " ON a.sBranchCd = b.sBranchCd" + 
                            " LEFT JOIN Inv_Type c" + 
                                " ON a.sInvTypCd = c.sInvTypCd" + 
                        ", Client_Master d" + 
                " WHERE a.sSupplier = d.sClientID" + 
                    " AND a.cTranStat = '2'" +
                    " AND a.sTansNox LIKE " + SQLUtil.toSQL(poGRider.getBranchCode());
    }
    
    private String getSQ_POReturn(){
        String lsTranStat = String.valueOf(pnTranStat);
        String lsCondition = "";
        if (lsTranStat.length() == 1) {
            lsCondition = "a.cTranStat = " + SQLUtil.toSQL(lsTranStat);
        } else {
            for (int lnCtr = 0; lnCtr <= lsTranStat.length() -1; lnCtr++){
                lsCondition = lsCondition + SQLUtil.toSQL(String.valueOf(lsTranStat.charAt(lnCtr))) + ",";
            }
            lsCondition = "(" + lsCondition.substring(0, lsCondition.length()-1) + ")";
            lsCondition = "a.cTranStat IN " + lsCondition;
        }
        
        return MiscUtil.addCondition("SELECT " +
                                        "  a.sTransNox" +
                                        ", a.sBranchCd" + 
                                        ", a.dTransact" +
                                        ", a.sInvTypCd" +
                                        ", a.nTranTotl" + 
                                        ", b.sBranchNm" + 
                                        ", c.sDescript" + 
                                        ", d.sClientNm" + 
                                        ", a.cTranStat" + 
                                        ", IFNULL(e.sReferNox, '') xReferNox" +
                                        ", CASE " +
                                            " WHEN a.cTranStat = '0' THEN 'OPEN'" +
                                            " WHEN a.cTranStat = '1' THEN 'CLOSED'" +
                                            " WHEN a.cTranStat = '2' THEN 'POSTED'" +
                                            " WHEN a.cTranStat = '3' THEN 'CANCELLED'" +
                                            " WHEN a.cTranStat = '4' THEN 'VOID'" +
                                            " END AS xTranStat" +
                                    " FROM PO_Return_Master a" + 
                                            " LEFT JOIN Branch b" + 
                                                " ON a.sBranchCd = b.sBranchCd" + 
                                            " LEFT JOIN Inv_Type c" + 
                                                " ON a.sInvTypCd = c.sInvTypCd" + 
                                            " LEFT JOIN PO_Receiving_Master e" +
                                                " ON a.sPOTransx = e.sTransNox" +
                                        ", Client_Master d" + 
                                    " WHERE a.sSupplier = d.sClientID", lsCondition);
    }
    
    public void setTranStat(int fnValue){this.pnTranStat = fnValue;}
    
    //callback methods
    public void setCallBack(IMasterDetail foCallBack){
        poCallBack = foCallBack;
    }
    
    private void MasterRetreived(int fnRow){
        if (poCallBack == null) return;
        
        poCallBack.MasterRetreive(fnRow);
    }
    
    private void DetailRetreived(int fnRow){
        if (poCallBack == null) return;
        
        poCallBack.DetailRetreive(fnRow);
    }
    
    public String getMessage(){return psMessage;}
    public String getErrMsg(){return psErrMesg;}
    public String getSourceCd(){return pxeSourceCd;}
    
    //Member Variables
    private GRider poGRider;
    private POReturn poControl;
    private UnitPOReturnMaster poData;
    private final UnitPOReturnDetail poDetail = new UnitPOReturnDetail();
    private ArrayList<UnitPOReturnDetail> poDetailArr;
    
    private String psBranchCd;
    private int pnEditMode;
    private String psUserIDxx;
    private boolean pbWithParent;
    private int pnTranStat = 0;
    private IMasterDetail poCallBack;
    
    private final String pxeModuleName = "XMPOReturn";
    private final String pxeSourceCd = "PORt";
    private final Double pxeTaxWHeldRate = 0.01;
    private final Double pxeTaxRate = 0.12;
    private final Double pxeTaxExcludRte = 1.12;
    
    private String psMessage;
    private String psErrMesg;
}
