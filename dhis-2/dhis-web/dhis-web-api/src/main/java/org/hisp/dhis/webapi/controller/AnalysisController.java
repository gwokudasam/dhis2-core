package org.hisp.dhis.webapi.controller;

/*
 * Copyright (c) 2004-2018, University of Oslo
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation
 * and/or other materials provided with the distribution.
 * Neither the name of the HISP project nor the names of its contributors may
 * be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

import com.google.common.collect.Sets;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hisp.dhis.analysis.FollowupAnalysisParams;
import org.hisp.dhis.analysis.FollowupParams;
import org.hisp.dhis.analysis.MinMaxOutlierAnalysisParams;
import org.hisp.dhis.analysis.StdDevOutlierAnalysisParams;
import org.hisp.dhis.analysis.UpdateFollowUpForDataValuesRequest;
import org.hisp.dhis.analysis.ValidationRulesAnalysisParams;
import org.hisp.dhis.common.DhisApiVersion;
import org.hisp.dhis.common.Grid;
import org.hisp.dhis.common.GridHeader;
import org.hisp.dhis.common.cache.CacheStrategy;
import org.hisp.dhis.dataanalysis.DataAnalysisService;
import org.hisp.dhis.dataanalysis.FollowupAnalysisService;
import org.hisp.dhis.dataanalysis.MinMaxOutlierAnalysisService;
import org.hisp.dhis.dataanalysis.StdDevOutlierAnalysisService;
import org.hisp.dhis.dataelement.DataElement;
import org.hisp.dhis.dataelement.DataElementCategoryOptionCombo;
import org.hisp.dhis.dataelement.DataElementCategoryService;
import org.hisp.dhis.dataelement.DataElementService;
import org.hisp.dhis.dataset.DataSetService;
import org.hisp.dhis.datavalue.DataValue;
import org.hisp.dhis.datavalue.DataValueService;
import org.hisp.dhis.datavalue.DeflatedDataValue;
import org.hisp.dhis.dxf2.webmessage.WebMessageException;
import org.hisp.dhis.dxf2.webmessage.WebMessageUtils;
import org.hisp.dhis.i18n.I18n;
import org.hisp.dhis.i18n.I18nFormat;
import org.hisp.dhis.i18n.I18nManager;
import org.hisp.dhis.organisationunit.OrganisationUnit;
import org.hisp.dhis.organisationunit.OrganisationUnitService;
import org.hisp.dhis.period.Period;
import org.hisp.dhis.period.PeriodService;
import org.hisp.dhis.system.grid.GridUtils;
import org.hisp.dhis.system.grid.ListGrid;
import org.hisp.dhis.validation.ValidationAnalysisParams;
import org.hisp.dhis.validation.ValidationResult;
import org.hisp.dhis.validation.ValidationRuleGroup;
import org.hisp.dhis.validation.ValidationRuleService;
import org.hisp.dhis.validation.ValidationService;
import org.hisp.dhis.validation.comparator.ValidationResultComparator;
import org.hisp.dhis.webapi.mvc.annotation.ApiVersion;
import org.hisp.dhis.webapi.utils.ContextUtils;
import org.joda.time.DateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.ResponseStatus;

import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.hisp.dhis.system.util.CodecUtils.filenameEncode;

/**
 * analysis endpoint to perform analysis and generate reports
 *
 * @author Joao Antunes
 */
@Controller
@RequestMapping( value = AnalysisController.RESOURCE_PATH )
@ApiVersion( { DhisApiVersion.DEFAULT, DhisApiVersion.ALL } )
@PreAuthorize( "hasRole('ALL') or hasRole('F_PERFORM_MAINTENANCE')" )
public class AnalysisController
{
    public static final String RESOURCE_PATH = "/analysis";

    private static final Log log = LogFactory.getLog( AnalysisController.class );

    private static final String KEY_ANALYSIS_DATA_VALUES = "analysisDataValues";

    private static final String KEY_VALIDATIONRESULT = "validationResult";

    @Autowired
    private ContextUtils contextUtils;

    @Autowired
    private I18nManager i18nManager;

    @Autowired
    private ValidationService validationService;

    @Autowired
    private ValidationRuleService validationRuleService;

    @Autowired
    private OrganisationUnitService organisationUnitService;

    @Autowired
    private PeriodService periodService;

    @Autowired
    private DataSetService dataSetService;

    @Autowired
    private DataElementService dataElementService;

    @Autowired
    private DataElementCategoryService dataElementCategoryService;

    @Autowired
    private DataValueService dataValueService;

    @Autowired
    private StdDevOutlierAnalysisService stdDevOutlierAnalysisService;

    @Autowired
    private MinMaxOutlierAnalysisService minMaxOutlierAnalysisService;

    @Autowired
    private FollowupAnalysisService followupAnalysisService;

    @RequestMapping( value = "/validationRules", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE )
    @ResponseStatus( HttpStatus.OK )
    public @ResponseBody
    List<ValidationResult> performValidationRulesAnalysis( @RequestBody ValidationRulesAnalysisParams validationRulesAnalysisParams, HttpSession session )
        throws WebMessageException
    {
        I18nFormat format = i18nManager.getI18nFormat();

        ValidationRuleGroup group = null;
        if ( validationRulesAnalysisParams.getValidationRuleGroupId() != null ) {
            group = validationRuleService.getValidationRuleGroup( validationRulesAnalysisParams.getValidationRuleGroupId() );
        }

        OrganisationUnit organisationUnit = organisationUnitService.getOrganisationUnit( validationRulesAnalysisParams.getOrganisationUnitId() );
        if ( organisationUnit == null )
        {
            throw new WebMessageException( WebMessageUtils.badRequest( "No organisation unit defined" ) );
        }

        ValidationAnalysisParams params = validationService.newParamsBuilder( group, organisationUnit, format.parseDate( validationRulesAnalysisParams.getStarteDate() ), format.parseDate( validationRulesAnalysisParams.getEndDate() ) )
            .withIncludeOrgUnitDescendants( true )
            .withPersistResults( validationRulesAnalysisParams.isPersist() )
            .withSendNotifications( validationRulesAnalysisParams.isNotification() )
            .withMaxResults( ValidationService.MAX_INTERACTIVE_ALERTS )
            .build();

        List<ValidationResult> validationResults = new ArrayList<>( validationService.validationAnalysis( params ) );

        Collections.sort( validationResults, new ValidationResultComparator() );

        session.setAttribute( KEY_VALIDATIONRESULT, validationResults );

        return validationResultsListToResponse( validationResults );

    }

    @RequestMapping( value = "/stdDevOutlier", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE )
    @ResponseStatus( HttpStatus.OK )
    public @ResponseBody
    List<DeflatedDataValue> performStdDevOutlierAnalysis( @RequestBody StdDevOutlierAnalysisParams stdDevOutlierAnalysisParams, HttpSession session )
        throws WebMessageException
    {
        I18nFormat format = i18nManager.getI18nFormat();

        OrganisationUnit organisationUnit = organisationUnitService.getOrganisationUnit( stdDevOutlierAnalysisParams.getOrganisationUnitId() );
        if ( organisationUnit == null )
        {
            throw new WebMessageException( WebMessageUtils.badRequest( "No organisation unit defined" ) );
        }

        Collection<Period> periods = periodService.getPeriodsBetweenDates( format.parseDate( stdDevOutlierAnalysisParams.getFromDate() ), format.parseDate( stdDevOutlierAnalysisParams.getToDate() ) );

        Set<DataElement> dataElements = new HashSet<>();

        for ( String uid : stdDevOutlierAnalysisParams.getDataSetIds() )
        {
            dataElements.addAll( dataSetService.getDataSet( uid ).getDataElements() );
        }

        Date from = new DateTime( format.parseDate( stdDevOutlierAnalysisParams.getFromDate() ) ).minusYears( 2 ).toDate();

        log.info( "From date: " + stdDevOutlierAnalysisParams.getFromDate() + ", To date: " + stdDevOutlierAnalysisParams.getToDate() + ", Organisation unit: " + organisationUnit
            + ", Std dev: " + stdDevOutlierAnalysisParams.getStandardDeviation() );

        log.info( "Nr of data elements: " + dataElements.size() + " Nr of periods: " + periods.size() + "for Standard Deviation Outlier Analysis" );

        List<DeflatedDataValue> dataValues = new ArrayList( stdDevOutlierAnalysisService.analyse( Sets.newHashSet( organisationUnit ), dataElements, periods, stdDevOutlierAnalysisParams.getStandardDeviation(), from ) );

        session.setAttribute( KEY_ANALYSIS_DATA_VALUES, dataValues );

        return deflatedValuesListToResponse( dataValues );
    }

    @RequestMapping( value = "/minMaxOutlier", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE )
    @ResponseStatus( HttpStatus.OK )
    public @ResponseBody
    List<DeflatedDataValue> performMinMaxOutlierAnalysis( @RequestBody MinMaxOutlierAnalysisParams minMaxOutlierAnalysisParams, HttpSession session )
        throws WebMessageException
    {
        I18nFormat format = i18nManager.getI18nFormat();

        OrganisationUnit organisationUnit = organisationUnitService.getOrganisationUnit( minMaxOutlierAnalysisParams.getOrganisationUnitId() );
        if ( organisationUnit == null )
        {
            throw new WebMessageException( WebMessageUtils.badRequest( "No organisation unit defined" ) );
        }

        Collection<Period> periods = periodService.getPeriodsBetweenDates( format.parseDate( minMaxOutlierAnalysisParams.getFromDate() ), format.parseDate( minMaxOutlierAnalysisParams.getToDate() ) );

        Set<DataElement> dataElements = new HashSet<>();

        if ( minMaxOutlierAnalysisParams.getDataSetIds() != null )
        {
            for ( String uid : minMaxOutlierAnalysisParams.getDataSetIds() )
            {
                dataElements.addAll( dataSetService.getDataSet( uid ).getDataElements() );
            }
        }

        Date from = new DateTime( format.parseDate( minMaxOutlierAnalysisParams.getFromDate() ) ).minusYears( 2 ).toDate();

        log.info( "From date: " + minMaxOutlierAnalysisParams.getFromDate() + ", To date: " + minMaxOutlierAnalysisParams.getToDate() + ", Organisation unit: " + organisationUnit );

        log.info( "Nr of data elements: " + dataElements.size() + " Nr of periods: " + periods.size() + " for Min Max Outlier Analysis" );

        List<DeflatedDataValue> dataValues = new ArrayList( minMaxOutlierAnalysisService.analyse( Sets.newHashSet( organisationUnit ), dataElements, periods, null, from ) );

        session.setAttribute( KEY_ANALYSIS_DATA_VALUES, dataValues );

        return deflatedValuesListToResponse( dataValues );
    }

    @RequestMapping( value = "/followup", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE )
    @ResponseStatus( HttpStatus.OK )
    public @ResponseBody
    List<DeflatedDataValue> performFollowupAnalysis( @RequestBody FollowupAnalysisParams followupAnalysisParams, HttpSession session )
        throws WebMessageException
    {
        I18nFormat format = i18nManager.getI18nFormat();

        OrganisationUnit organisationUnit = organisationUnitService.getOrganisationUnit( followupAnalysisParams.getOrganisationUnitId() );
        if ( organisationUnit == null )
        {
            throw new WebMessageException( WebMessageUtils.badRequest( "No organisation unit defined" ) );
        }

        Date startDate = null;
        Date endDate = null;
        if ( followupAnalysisParams.getStartDate() != null && followupAnalysisParams.getEndDate() != null )
        {
            startDate = new DateTime( format.parseDate( followupAnalysisParams.getStartDate() ) ).toDate();
            endDate = new DateTime( format.parseDate( followupAnalysisParams.getEndDate() ) ).toDate();
        }

        List<DeflatedDataValue> dataValues = new ArrayList( followupAnalysisService.getFollowupDataValuesBetweenInterval( organisationUnit, followupAnalysisParams.getDataSetId(), DataAnalysisService.MAX_OUTLIERS + 1, startDate, endDate ) ); // +1 to detect overflow

        session.setAttribute( KEY_ANALYSIS_DATA_VALUES, dataValues );

        return deflatedValuesListToResponse( dataValues );
    }

    @RequestMapping( value = "/followup/mark", method = RequestMethod.POST, consumes = MediaType.APPLICATION_JSON_VALUE )
    @ResponseStatus( HttpStatus.NO_CONTENT )
    public @ResponseBody
    void markDataValues( @RequestBody UpdateFollowUpForDataValuesRequest updateFollowUpForDataValuesRequest )
    {
        List<DataValue> dataValues = new ArrayList<>();
        for ( FollowupParams followup : updateFollowUpForDataValuesRequest.getFollowups() )
        {
            DataElement dataElement = dataElementService.getDataElement( followup.getDataElementId() );
            Period period = periodService.getPeriod( followup.getPeriodId() );
            OrganisationUnit source = organisationUnitService.getOrganisationUnit( followup.getOrganisationUnitId() );
            DataElementCategoryOptionCombo categoryOptionCombo = dataElementCategoryService.getDataElementCategoryOptionCombo( followup.getCategoryOptionComboId() );
            DataElementCategoryOptionCombo attributeOptionCombo = dataElementCategoryService.getDataElementCategoryOptionCombo( followup.getAttributeOptionComboId() );

            DataValue dataValue = dataValueService.getDataValue( dataElement, period, source, categoryOptionCombo, attributeOptionCombo );

            if ( dataValue != null )
            {
                dataValue.setFollowup( followup.isFollowup() );
                dataValues.add( dataValue );
            }

            log.info( followup.isFollowup() ? "Data value will be marked for follow-up" : "Data value will be unmarked for follow-up" );
        }

        if ( dataValues.size() > 0 )
        {
            dataValueService.updateDataValues( dataValues );
        }
    }

    @RequestMapping( value = "/report.pdf", method = RequestMethod.GET )
    public void getPdfReport( HttpSession session, HttpServletResponse response ) throws Exception
    {
        List<DeflatedDataValue> results = (List<DeflatedDataValue>) session.getAttribute( KEY_ANALYSIS_DATA_VALUES );
        Grid grid = generateAnalysisReportGridFromResults( results );

        String filename = filenameEncode( grid.getTitle() ) + ".pdf";
        contextUtils.configureResponse( response, ContextUtils.CONTENT_TYPE_PDF, CacheStrategy.RESPECT_SYSTEM_SETTING, filename, false );

        GridUtils.toPdf( grid, response.getOutputStream() );
    }

    @RequestMapping( value = "/report.xls", method = RequestMethod.GET )
    public void getXlsReport( HttpSession session, HttpServletResponse response ) throws Exception
    {
        List<DeflatedDataValue> results = (List<DeflatedDataValue>) session.getAttribute( KEY_ANALYSIS_DATA_VALUES );
        Grid grid = generateAnalysisReportGridFromResults( results );

        String filename = filenameEncode( grid.getTitle() ) + ".xls";
        contextUtils.configureResponse( response, ContextUtils.CONTENT_TYPE_EXCEL, CacheStrategy.RESPECT_SYSTEM_SETTING, filename, false );

        GridUtils.toXls( grid, response.getOutputStream() );
    }

    @RequestMapping( value = "/report.csv", method = RequestMethod.GET )
    public void getCSVReport( HttpSession session, HttpServletResponse response ) throws Exception
    {
        List<DeflatedDataValue> results = (List<DeflatedDataValue>) session.getAttribute( KEY_ANALYSIS_DATA_VALUES );
        Grid grid = generateAnalysisReportGridFromResults( results );

        String filename = filenameEncode( grid.getTitle() ) + ".csv";
        contextUtils.configureResponse( response, ContextUtils.CONTENT_TYPE_CSV, CacheStrategy.RESPECT_SYSTEM_SETTING, filename, false );

        GridUtils.toCsv( grid, response.getWriter() );
    }

    @RequestMapping( value = "validationRules/report.pdf", method = RequestMethod.GET )
    public void getValidationRulesPdfReport( HttpSession session, HttpServletResponse response ) throws Exception
    {
        List<ValidationResult> results = (List<ValidationResult>) session.getAttribute( KEY_ANALYSIS_DATA_VALUES );
        Grid grid = generateValidationRulesReportGridFromResults( results );

        String filename = filenameEncode( grid.getTitle() ) + ".pdf";
        contextUtils.configureResponse( response, ContextUtils.CONTENT_TYPE_PDF, CacheStrategy.RESPECT_SYSTEM_SETTING, filename, false );

        GridUtils.toPdf( grid, response.getOutputStream() );
    }

    @RequestMapping( value = "validationRules/report.xls", method = RequestMethod.GET )
    public void getValidationRulesXlsReport( HttpSession session, HttpServletResponse response ) throws Exception
    {
        List<ValidationResult> results = (List<ValidationResult>) session.getAttribute( KEY_ANALYSIS_DATA_VALUES );
        Grid grid = generateValidationRulesReportGridFromResults( results );

        String filename = filenameEncode( grid.getTitle() ) + ".xls";
        contextUtils.configureResponse( response, ContextUtils.CONTENT_TYPE_EXCEL, CacheStrategy.RESPECT_SYSTEM_SETTING, filename, false );

        GridUtils.toPdf( grid, response.getOutputStream() );
    }

    @RequestMapping( value = "validationRules/report.csv", method = RequestMethod.GET )
    public void getValidationRulesCSVReport( HttpSession session, HttpServletResponse response ) throws Exception
    {
        List<ValidationResult> results = (List<ValidationResult>) session.getAttribute( KEY_VALIDATIONRESULT );
        Grid grid = generateValidationRulesReportGridFromResults( results );

        String filename = filenameEncode( grid.getTitle() ) + ".csv";
        contextUtils.configureResponse( response, ContextUtils.CONTENT_TYPE_CSV, CacheStrategy.RESPECT_SYSTEM_SETTING, filename, false );

        GridUtils.toPdf( grid, response.getOutputStream() );
    }

    private Grid generateAnalysisReportGridFromResults( List<DeflatedDataValue> results )
    {
        Grid grid = new ListGrid();
        if ( results != null )
        {
            I18nFormat format = i18nManager.getI18nFormat();
            I18n i18n = i18nManager.getI18n();

            grid.setTitle( i18n.getString( "data_analysis_report" ) );

            grid.addHeader( new GridHeader( i18n.getString( "dataelement" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "source" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "period" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "min" ), false, false ) );
            grid.addHeader( new GridHeader( i18n.getString( "value" ), false, false ) );
            grid.addHeader( new GridHeader( i18n.getString( "max" ), false, false ) );

            for ( DeflatedDataValue dataValue : results )
            {
                Period period = dataValue.getPeriod();

                grid.addRow();
                grid.addValue( dataValue.getDataElementName() );
                grid.addValue( dataValue.getSourceName() );
                grid.addValue( format.formatPeriod( period ) );
                grid.addValue( dataValue.getMin() );
                grid.addValue( dataValue.getValue() );
                grid.addValue( dataValue.getMax() );
            }
        }

        return grid;
    }

    private Grid generateValidationRulesReportGridFromResults( List<ValidationResult> results )
    {
        Grid grid = new ListGrid();
        if ( results != null )
        {
            I18nFormat format = i18nManager.getI18nFormat();
            I18n i18n = i18nManager.getI18n();

            grid.setTitle( i18n.getString( "data_quality_report" ) );

            grid.addHeader( new GridHeader( i18n.getString( "source" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "period" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "validation_rule" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "importance" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "left_side_description" ), false, true ) );
            grid.addHeader( new GridHeader( i18n.getString( "value" ), false, false ) );
            grid.addHeader( new GridHeader( i18n.getString( "operator" ), false, false ) );
            grid.addHeader( new GridHeader( i18n.getString( "value" ), false, false ) );
            grid.addHeader( new GridHeader( i18n.getString( "right_side_description" ), false, true ) );

            for ( ValidationResult validationResult : results )
            {
                OrganisationUnit unit = validationResult.getOrganisationUnit();
                Period period = validationResult.getPeriod();

                grid.addRow();
                grid.addValue( unit.getName() );
                grid.addValue( format.formatPeriod( period ) );
                grid.addValue( validationResult.getValidationRule().getName() );
                grid.addValue( i18n.getString( validationResult.getValidationRule().getImportance().toString().toLowerCase() ) );
                grid.addValue( validationResult.getValidationRule().getLeftSide().getDescription() );
                grid.addValue( String.valueOf( validationResult.getLeftsideValue() ) );
                grid.addValue( i18n.getString( validationResult.getValidationRule().getOperator().toString() ) );
                grid.addValue( String.valueOf( validationResult.getRightsideValue() ) );
                grid.addValue( validationResult.getValidationRule().getRightSide().getDescription() );
            }
        }

        return grid;
    }

    private List<DeflatedDataValue> deflatedValuesListToResponse( List<DeflatedDataValue> deflatedDataValues )
    {
        I18nFormat format = i18nManager.getI18nFormat();
        if ( deflatedDataValues == null )
        {
            return Collections.emptyList();
        }

        List<DeflatedDataValue> dataValuesToResponse = deflatedDataValues;
        if ( deflatedDataValues.size() > DataAnalysisService.MAX_OUTLIERS )
        {
            return deflatedDataValues.subList( 0, DataAnalysisService.MAX_OUTLIERS );
        }

        for ( DeflatedDataValue dataValue : dataValuesToResponse )
        {
            dataValue.getPeriod().setName( format.formatPeriod( dataValue.getPeriod() ) );
        }

        return dataValuesToResponse;
    }

    private List<ValidationResult> validationResultsListToResponse( List<ValidationResult> validationResults )
    {
        I18nFormat format = i18nManager.getI18nFormat();
        if ( validationResults == null )
        {
            return Collections.emptyList();
        }

        for ( ValidationResult validationResult : validationResults )
        {
            validationResult.getPeriod().setName( format.formatPeriod( validationResult.getPeriod() ) );
        }

        return validationResults;
    }
}
