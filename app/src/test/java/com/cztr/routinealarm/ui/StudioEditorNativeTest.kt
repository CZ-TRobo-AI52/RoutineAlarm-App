package com.cztr.routinealarm.ui
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.cztr.routinealarm.ui.studio.*
import com.cztr.routinealarm.ui.design.R52Colors
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28,31])
class StudioEditorNativeTest {
 @Test fun authorizedCreateActionDispatchesWithoutSaving() {
  var calls=0
  val context=RuntimeEnvironment.getApplication()
  val state=StudioUiState(StudioFilter.ALL,StudioFilter.entries,emptyList(),null)
  val ctor=StudioScreenView::class.java.constructors.firstOrNull { it.parameterCount==7 }
  val screen=if(ctor!=null) ctor.newInstance(context,state,R52Colors.Light,({_:StudioFilter ->}),({_:String ->}),({calls++}),null) as StudioScreenView
   else StudioScreenView(context,state,R52Colors.Light,{},{})
  val action=views(screen).filterIsInstance<Button>().firstOrNull { it.tag=="studio_create" }
  assertNotNull("Authorized host must be able to offer create routine",action)
  assertEquals(0,calls);action!!.performClick();assertEquals(1,calls)
 }

 @Test fun createsPausedPayloadAndLocksDuplicateSave() {
  val sent=mutableListOf<StudioSaveRequest>()
  val view=editor { sent+=it }
  fill(view);button(view,"studio_save").performClick();button(view,"studio_save").performClick()
  assertEquals(1,sent.size);assertFalse(sent[0].event.enabled)
  assertEquals("Neu",sent[0].event.title);assertEquals(23,sent[0].event.hour)
  assertEquals(59,sent[0].event.minute);assertEquals(setOf(java.time.DayOfWeek.MONDAY),sent[0].event.daysOfWeek)
  assertEquals(0L,sent[0].expectedVersion);assertEquals(7L,sent[0].expectedPlanVersion);assertEquals("epoch-A",sent[0].expectedRestoreEpoch)
 }
 @Test fun invalidInputDoesNotSubmitAndCancelDoesNotSave() {
  var saves=0;var cancels=0
  val v=StudioEditorView(RuntimeEnvironment.getApplication(),StudioEditorDraft.create(7,"epoch-A"),R52Colors.Light,{saves++},{cancels++})
  button(v,"studio_save").performClick();fill(v);field(v,"hour").setText("24")
  button(v,"studio_save").performClick();assertEquals(0,saves)
  button(v,"studio_cancel").performClick();assertEquals(1,cancels)
 }
 @Test fun retryKeepsRequestButChangedPayloadGetsNewRequest() {
  val sent=mutableListOf<StudioSaveRequest>();val v=editor { sent+=it };fill(v)
  button(v,"studio_save").performClick();v.setSaveStatus(StudioSaveStatus.ERROR)
  assertEquals("Neu",field(v,"title").text.toString())
  button(v,"studio_save").performClick();assertEquals(sent[0],sent[1])
  v.setSaveStatus(StudioSaveStatus.ERROR);field(v,"title").setText("Anders");button(v,"studio_save").performClick()
  assertNotEquals(sent[1].requestId,sent[2].requestId)
 }
 @Test fun changedFreeTextWithSameDataClassStringGetsNewRequestIdentity() {
  val sent=mutableListOf<StudioSaveRequest>();val v=editor { sent+=it };fill(v)
  field(v,"title").setText("A, spokenText=B");field(v,"speech").setText("C")
  button(v,"studio_save").performClick();v.setSaveStatus(StudioSaveStatus.ERROR)
  field(v,"title").setText("A");field(v,"speech").setText("B, spokenText=C")
  button(v,"studio_save").performClick()
  assertEquals(sent[0].event.toString(),sent[1].event.toString())
  assertNotEquals(sent[0].event,sent[1].event)
  assertNotEquals(sent[0].requestId,sent[1].requestId)
 }
 @Test fun unknownEffectAndConflictNeverBlindlyRetry() {
  var saves=0;val v=editor { saves++ };fill(v);button(v,"studio_save").performClick()
  v.setSaveStatus(StudioSaveStatus.UNKNOWN_EFFECT);button(v,"studio_save").performClick()
  v.setSaveStatus(StudioSaveStatus.CONFLICT);button(v,"studio_save").performClick();assertEquals(1,saves)
 }
 @Test fun hierarchyRecreationRetainsDraftIdentityAndFencesInflightSave() {
  val sent=mutableListOf<StudioSaveRequest>();val v=editor { sent+=it };fill(v);button(v,"studio_save").performClick()
  val saved=android.util.SparseArray<android.os.Parcelable>();v.saveHierarchyState(saved)
  val next=editor { sent+=it };next.restoreHierarchyState(saved)
  assertEquals("Neu",field(next,"title").text.toString());assertEquals(v.currentDraft().eventId,next.currentDraft().eventId)
  assertFalse(button(next,"studio_save").isEnabled);assertEquals(v.currentDraft().requestId,next.currentDraft().requestId)
  next.setSaveStatus(StudioSaveStatus.ERROR);button(next,"studio_save").performClick();assertEquals(sent[0],sent[1])
 }
 @Test fun editKeepsIdVersionAndAllFieldsButPauses() {
  val e=com.cztr.routinealarm.RoutineEvent("existing",setOf(java.time.DayOfWeek.SUNDAY),12,34,"Alt","Sprache",com.cztr.routinealarm.EventCategory.WORK,com.cztr.routinealarm.EventMode.ANNOUNCEMENT,true)
  var result:StudioSaveRequest?=null
  val v=StudioEditorView(RuntimeEnvironment.getApplication(),StudioEditorDraft.edit(e,3,9,"epoch-B"),R52Colors.Dark,{result=it},{})
  field(v,"title").setText("Bearbeitet");button(v,"studio_save").performClick()
  assertEquals(e.copy(title="Bearbeitet",enabled=false),result!!.event);assertEquals(3L,result!!.expectedVersion)
  assertEquals(9L,result!!.expectedPlanVersion);assertEquals("epoch-B",result!!.expectedRestoreEpoch)
 }
 @Test fun narrowLargeFontControlsRemainScrollableWithFullLabelsInBothPalettes() {
  org.robolectric.RuntimeEnvironment.setFontScale(2f)
  try { for(colors in listOf(R52Colors.Light,R52Colors.Dark)) {
   val controller=org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
   try {
    val v=StudioEditorView(controller.get(),StudioEditorDraft.create(7,"epoch-A"),colors,{},{})
    controller.get().setContentView(v)
    val decor=controller.get().window.decorView
    decor.measure(View.MeasureSpec.makeMeasureSpec(320,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY));decor.layout(0,0,320,480)
    for(b in views(v).filterIsInstance<Button>()) {
     val rect=android.graphics.Rect(0,0,b.width,b.height);v.offsetDescendantRectToMyCoords(b,rect);v.scrollTo(0,rect.top)
     val visible=android.graphics.Rect();assertTrue(b.getGlobalVisibleRect(visible))
     assertEquals(b.width,visible.width());assertEquals(b.height,visible.height());assertTrue(b.height>=48)
     assertNotNull(b.layout);assertEquals(b.text.length,b.layout.getLineEnd(b.layout.lineCount-1))
     assertEquals(0,(0 until b.layout.lineCount).sumOf { b.layout.getEllipsisCount(it) })
    }
   } finally { controller.pause().stop().destroy() }
  }} finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
 }
 private fun editor(save:(StudioSaveRequest)->Unit)=StudioEditorView(RuntimeEnvironment.getApplication(),StudioEditorDraft.create(7,"epoch-A"),R52Colors.Light,save,{})
 private fun field(v:View,key:String)=views(v).filterIsInstance<android.widget.EditText>().single { it.tag==key }
 private fun button(v:View,key:String)=views(v).filterIsInstance<Button>().single { it.tag==key }
 private fun fill(v:View) {
  field(v,"title").setText("Neu");field(v,"speech").setText("Hallo");field(v,"hour").setText("23");field(v,"minute").setText("59")
  views(v).filterIsInstance<android.widget.CheckBox>().single { it.tag=="day:MONDAY" }.isChecked=true
 }

 private fun views(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{views(v.getChildAt(it))} else emptyList()
}
