xquery version "1.0";

import module namespace style = "http://exist-db.org/mods-style" at "../../../modules/style.xqm";
import module namespace mods = "http://www.loc.gov/mods/v3" at "../modules/mods.xqm";
import module namespace config = "http://exist-db.org/mods/config" at "../config.xqm";
import module namespace xmldb = "http://exist-db.org/xquery/xmldb";

declare namespace xf="http://www.w3.org/2002/xforms";
declare namespace xforms="http://www.w3.org/2002/xforms";
declare namespace ev="http://www.w3.org/2001/xml-events";
declare namespace xlink="http://www.w3.org/1999/xlink";

declare function xf:get-temp-collection() {
    let $collection := collection($config:mods-temp-collection)
    return
        $config:mods-temp-collection
};

let $title := 'MODS Record Editor'

(: get REST URL parameters :)
let $id-param := request:get-parameter('id', 'new')
let $show-all-string := request:get-parameter('show-all', 'false')
let $show-all := if ($show-all-string = 'true') then true() else false()

(: check if a host is specified for related item :)
let $host := request:get-parameter('host', ())

(: if no tab is specified, we default to the compact-a tab :)
let $tab-id := request:get-parameter('tab-id', 'compact-a')

(: display the label attached to the tab to the user :)
let $tab-data := concat($style:db-path-to-app, '/edit/tab-data.xml')
let $tab-label := doc($tab-data)/tabs/tab[tab-id=$tab-id]/label 

(: if document type is specified then we will need to use that instance as the template :)
let $type-data := concat($style:db-path-to-app, '/code-tables/document-type-codes.xml')
let $type-request := request:get-parameter('type', '')
(: NB: else with "<xf:output value="./mods:extension/*[2]"/>" does not work. :)
let $type-value := if ($type-request) then $type-request else doc($type-data)//item[value = <xf:output value="./mods:extension/*[2]"/>]/label
let $type-label := doc($type-data)//item[value = $type-value]/label

(: look for an alternate data collection in the URL, else use the default mods data collection :)
let $user := request:get-parameter('user', '')

let $destination := <xf:output value="./mods:extension/*[1]"/>

let $collection := request:get-parameter('collection', '')
let $tempCollection := xf:get-temp-collection()

let $data-collection :=
   if ($collection)
   then $collection
      else if ($user)
         then concat('/db/home/', $user, '/apps/mods/data')
         else '/db/org/library/apps/mods/data'

(: check to see if we have a  :)
let $new := if ($id-param = '' or $id-param = 'new')
        then true()
        else false()
        
(: if we do not have an incomming ID or it the ID is new then create one to use 
   Note that for testing you can use the first five chars of the UUID substring(util:uuid(), 1, 5)
:)
let $id :=
   if ($new)
        then concat("uuid-", util:uuid())
        else $id-param

(: if we are creating a new record then we need to call get-instance.xq with new=true to tell it to get the entire template :)
let $create-new-from-template :=
   if ($new)
      then (
         (: copy the template into data and update it with a new UUID :)
         let $template-path :=
            if ($type-value='default')
               then concat($style:db-path-to-app, '/edit/new-instance.xml')
               else concat($style:db-path-to-app, '/edit/instances/', $type-value, '.xml')
         let $template := doc($template-path)
         let $new-file-name := concat($id, '.xml')
         (: store it in the right location :)
         let $stored := xmldb:store($tempCollection, $new-file-name, $template)
         let $new-file-path := concat($data-collection, '/', $new-file-name)
         let $languageOfResource := request:get-parameter("languageOfResource", "")
         let $scriptOfResource := request:get-parameter("scriptOfResource", "")
         let $transliterationOfResource := request:get-parameter("transliterationOfResource", "")
         let $languageOfCataloging := request:get-parameter("languageOfCataloging", "")
         let $scriptOfCataloging := request:get-parameter("scriptOfCataloging", "")
         let $scriptTypeOfResource := doc("/db/org/library/apps/mods/code-tables/language-3-type-codes.xml")/code-table/items/item[value = $languageOfResource]/data(scriptClassifier)
         let $scriptTypeOfCataloging := doc("/db/org/library/apps/mods/code-tables/language-3-type-codes.xml")/code-table/items/item[value = $languageOfCataloging]/data(scriptClassifier)
         let $doc := doc($stored)
         
         (: note that we can not use "update replace" if we want to keep the default namespace :)
         return (
            update value $doc/mods:mods/@ID with $id
            ,
            (: Save language and script of resource. :)
            let $language-insert:=
                <mods:language>
                    <mods:languageTerm authority="iso639-2b" type="code">
                        {$languageOfResource}
                    </mods:languageTerm>
                    <mods:scriptTerm authority="iso15924" type="code">
                        {$scriptOfResource}
                    </mods:scriptTerm>
                </mods:language>
            return
            (: NB: does not work. Always inserted into end of document. :)
            update insert $language-insert into $doc/mods:mods
            ,
            (: Save creation date and language and script of cataloguing :)
            let $recordInfo-insert:=
                <mods:recordInfo lang="eng" script="Latn">
                    <mods:recordContentSource authority="marcorg">DE-16-158</mods:recordContentSource>
                    <mods:recordCreationDate encoding="w3cdtf">
                        {current-date()}
                    </mods:recordCreationDate>
                    <mods:recordChangeDate encoding="w3cdtf"/>
                    <mods:languageOfCataloging>
                        <mods:languageTerm authority="iso639-2b" type="code">
                            {$languageOfCataloging}
                        </mods:languageTerm>
                        <mods:scriptTerm authority="iso15924" type="code">
                            {$scriptOfCataloging}
                    </mods:scriptTerm>
                    </mods:languageOfCataloging>
                </mods:recordInfo>            
            return
            (: NB: does not work. Always inserted into end of document. :)
            update insert $recordInfo-insert into $doc/mods:mods
            ,
            (: Save name of user collection, name of template used, script type and transliteration scheme used into mods:extension. :)
            update insert
                <extension xmlns="http://www.loc.gov/mods/v3" xmlns:e="http://www.asia-europe.uni-heidelberg.de/">
                    <e:collection>{$data-collection}</e:collection>
                    <e:template>{$type-value}</e:template>
                    <e:scriptTypeOfResource>{$scriptTypeOfResource}</e:scriptTypeOfResource>
                    <e:scriptTypeOfCataloging>{$scriptTypeOfCataloging}</e:scriptTypeOfCataloging>
                    <e:transliterationOfResource>{$transliterationOfResource}</e:transliterationOfResource>                    
                </extension>
            into $doc/mods:mods
            ,
            if ($host) 
            then 
            (
                update value doc($stored)/mods:mods/mods:relatedItem/@xlink:href with $host,
                update value doc($stored)/mods:mods/mods:relatedItem/@type with "host"
            )
            else ()
         )
      ) else if (not(doc-available(concat($tempCollection, '/', $id, '.xml')))) then
        xmldb:copy($data-collection, $tempCollection, concat($id, '.xml'))
      else ()

(: this is the string we pass to instance id='save-data' src attribute :)
let $instance-src :=  concat('get-instance.xq?tab-id=', $tab-id, '&amp;id=', $id, '&amp;data=', $tempCollection)

let $user := xmldb:get-current-user()

let $body-collection := concat($style:db-path-to-app, '/edit/body')

(: this is the part of the form that we need for this tab :)
let $form-body := collection($body-collection)/div[@tab-id = $tab-id]

let $style :=
<style type="text/css"><![CDATA[
@namespace xf url(http://www.w3.org/2002/xforms);]]>
</style>

let $model :=
    <xf:model>
       
       <xf:instance xmlns="http://www.loc.gov/mods/v3" src="{$instance-src}" id="save-data"/>
       
       (: The full embodiment of the MODS schema, 3.3-3.4. :)
       <xf:instance xmlns="http://www.loc.gov/mods/v3" src="insert-templates.xml" id='insert-templates' readonly="true"/>
       
       (: A selection of elements and attributes from the MODS schema used for default records. :)
       <xf:instance xmlns="http://www.loc.gov/mods/v3" src="new-instance.xml" id='new-instance' readonly="true"/>

       (: Elements for the compact forms. :)
       <xf:instance xmlns="http://www.loc.gov/mods/v3" src="compact-template.xml" id='compact-template' readonly="true"/> 
       
       <xf:instance xmlns="" id="code-tables" src="codes-for-tab.xq?tab-id={$tab-id}" readonly="true"/>
       <!-- a title should be required, but having this bind will prevent a tab from being saved when clicking on another tab, if the user has not input a title.--> 
       <!--
       <xf:bind nodeset="instance('save-data')/mods:titleInfo/mods:title" required="true()"/>       
       -->
       <xf:instance xmlns="" id="save-results">
          <data>
             <message>Form loaded OK.</message>
          </data>
       </xf:instance>
                  
       <xf:submission id="save-submission" method="post"
          ref="instance('save-data')"
          action="save.xq?collection={$tempCollection}&amp;action=save" replace="instance"
          instance="save-results">
       </xf:submission>
       
       <xf:submission id="save-and-close-submission" method="post"
          ref="instance('save-data')"
          action="save.xq?collection={$tempCollection}&amp;action=close" replace="instance"
          instance="save-results">
       </xf:submission>
       
       <xf:submission id="cancel-submission" method="post"
          ref="instance('save-data')"
          action="save.xq?collection={$tempCollection}&amp;action=cancel" replace="instance"
          instance="save-results">
       </xf:submission>

</xf:model>

let $content :=
<div class="content">
    <span class="float-right">
    Editing record of type <strong>{$type-label}</strong>,
    with the title<strong><xf:output value="./mods:titleInfo/mods:title"/></strong>,
    on the <strong>{$tab-label}</strong> tab,
    to be saved in<strong>{$destination}</strong>
    </span>
    
    {mods:tabs($tab-id, $id, $show-all, $tempCollection)}
    
    <xf:submit submission="save-submission">
        <xf:label class="xforms-group-label-centered-general">&#160;Save</xf:label>
    </xf:submit>
    <xf:trigger>
        <xf:label class="xforms-group-label-centered-general">&#160;Save and Close</xf:label>
        <xf:action ev:event="DOMActivate">
            <xf:send submission="save-and-close-submission"/>
            <xf:load resource="../search/index.xml?reload=true" show="replace"/>
        </xf:action>
    </xf:trigger>
    <xf:trigger>
        <xf:label class="xforms-group-label-centered-general">&#160;Cancel</xf:label>
        <xf:action ev:event="DOMActivate">
            <xf:send submission="cancel-submission"/>
            <xf:load resource="../search/index.xml?reload=true" show="replace"/>
        </xf:action>
     </xf:trigger>
    <br/><br/>
    
    <!-- import the correct form body for this tab -->
    {$form-body}
    
    <!--
    <br/>
    <xf:submit submission="save-submission">
        <xf:label class="xforms-group-label-centered-general">Save</xf:label>
    </xf:submit>
    -->
    <!--
    <div class="debug">
        <xf:output value="count(instance('save-data')/*)">
           <xf:label>Root Element Count: </xf:label>
        </xf:output>
        <br/>
        <xf:output ref="instance('save-results')//message ">
           <xf:label>Message: </xf:label>
        </xf:output>
        
        <xf:output ref="instance('save-results')//mods:message ">
           <xf:label>MODS Message: </xf:label>
        </xf:output>
    </div>
    -->
    <!--
    <a href="get-instance.xq?id={$id}&amp;data={$data-collection}">View XML for the whole MODS record</a> -
    <a href="get-instance.xq?id={$id}&amp;tab-id={$tab-id}&amp;data={$data-collection}">View XML for the current tab</a>
    -->
</div>

return style:assemble-form('', attribute {'mods:dummy'} {'dummy'}, $style, $model, $content, false())
