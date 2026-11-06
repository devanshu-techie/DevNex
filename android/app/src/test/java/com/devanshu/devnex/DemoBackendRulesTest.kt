package com.devanshu.devnex

import com.devanshu.devnex.data.demo.DemoRemoteDataSource
import com.devanshu.devnex.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

private var passed = 0
private fun check(ok: Boolean, msg: String) { if (!ok) error("FAILED: $msg"); passed++; println("ok  - $msg") }
private suspend fun fails(block: suspend () -> Unit): String? = try { block(); null } catch (e: Exception) { e.message ?: e::class.simpleName }

class DemoBackendRulesTest {
@Test fun businessRulesHold() = runBlocking {
    val b = DemoRemoteDataSource()
    check(fails { b.signIn("owner@devnex.app", "wrong") } != null, "wrong password rejected")
    val dev = b.signIn("owner@devnex.app", "devnex123")
    check(dev.needsMfa && dev.isPlatformAdmin, "owner needs MFA and is platform admin")
    check(fails { b.verifyMfa("000000") } != null, "bad MFA code rejected")
    b.verifyMfa("123456")
    check(b.currentUser()?.needsMfa == false, "MFA passes with code")
    val ws = b.myWorkspaces()
    check(ws.size == 2, "owner sees 2 workspaces (multi-company membership)")
    val news = ws.first { it.slug == "velocity-sports" }
    val agency = ws.first { it.slug == "pixel-orbit" }
    check(news.modules.contains(Modules.COVERAGE) && !agency.modules.contains(Modules.COVERAGE), "coverage module only for newsroom")
    check(b.platformOverview().size == 2, "platform console lists companies")

    // reporter
    b.signIn("reporter@devnex.app", "devnex123")
    check(b.myWorkspaces().map { it.companyId } == listOf(news.companyId), "reporter only in newsroom")
    check(b.tasks(agency.companyId, null).isEmpty(), "reporter sees no agency tasks (isolation)")
    val visible = b.tasks(news.companyId, null)
    check(visible.none { it.id == "t-a2" }, "reporter can't see Video dept task")
    check(visible.any { it.id == "t-4" }, "reporter sees open pool task in Reporting")
    check(b.claimTask("t-a3") == ClaimResult.NOT_ELIGIBLE, "reporter not eligible for Video pool task")
    check(b.claimTask("t-4") == ClaimResult.SUCCESS, "reporter claims Reporting pool task")
    b.signIn("lead@devnex.app", "devnex123")
    check(b.claimTask("t-4") == ClaimResult.ALREADY_CLAIMED, "second claim fails")
    b.signIn("reporter@devnex.app", "devnex123")
    var t = b.tasks(news.companyId, null).first { it.id == "t-4" }
    check(fails { b.setTaskStatus(t.id, TaskStatus.SUBMITTED, t.version, null) } != null, "can't jump assigned → submitted")
    val v1 = b.setTaskStatus(t.id, TaskStatus.IN_PROGRESS, t.version, null)
    check(fails { b.setTaskStatus(t.id, TaskStatus.SUBMITTED, t.version, null) }?.contains("Someone else") == true, "stale version → conflict")
    b.setTaskStatus(t.id, TaskStatus.SUBMITTED, v1, null)
    check(fails { b.decideApproval(t.id, true, null) } != null, "member can't approve")
    b.signIn("lead@devnex.app", "devnex123")
    check(b.notifications(news.companyId).any { it.kind == "review" }, "lead notified of submission")
    check(fails { b.decideApproval(t.id, false, "") } != null, "changes need a note")
    check(b.decideApproval(t.id, true, null) == "STAGE_APPROVED", "lead approves stage 1")
    check(fails { b.decideApproval(t.id, true, null) }?.contains("Department Head") == true, "lead can't approve head stage")
    b.signIn("head@devnex.app", "devnex123")
    check(b.decideApproval(t.id, true, null) == "APPROVED", "head gives final approval")
    b.signIn("reporter@devnex.app", "devnex123")
    check(b.notifications(news.companyId).any { it.kind == "approved" }, "reporter notified of approval")
    check(fails { b.sendMessage("m1", news.companyId, "ch-ann", "hi") }?.contains("announcements") == true, "member can't post announcement")
    b.sendMessage("m2", news.companyId, "ch-general", "hello"); b.sendMessage("m2", news.companyId, "ch-general", "hello")
    check(b.messages("ch-general", null, 50).count { it.id == "m2" } == 1, "same client UUID never duplicates a message")

    // self approval
    b.signIn("owner@devnex.app", "devnex123"); b.verifyMfa("123456")
    val own = b.createTask(TaskDraft(news.companyId, "Own work", "", Priority.LOW, null, dev.id, false, null))
    var v = b.setTaskStatus(own.id, TaskStatus.IN_PROGRESS, own.version, null)
    b.setTaskStatus(own.id, TaskStatus.SUBMITTED, v, null)
    check(fails { b.decideApproval(own.id, true, null) }?.contains("own work") == true, "self-approval blocked")

    // invitations + new company
    val code = b.createInvitation(news.companyId, "new@devnex.app", "member", null)
    b.signUp("new@devnex.app", "password123", "New Person")
    check(b.myWorkspaces().isEmpty(), "new account starts with no company")
    b.acceptInvitation(code)
    check(b.myWorkspaces().size == 1, "invite joins the company")
    check(fails { b.acceptInvitation(code) } != null, "invite can't be reused")
    val cid = b.createCompany("My Startup", "my-startup", Industry.SOFTWARE)
    check(b.myWorkspaces().first { it.companyId == cid }.roleKey == "owner", "creator becomes owner of new company")
    check(b.departments(cid).map { it.name }.contains("Engineering"), "software template departments")

    // suspension
    b.signIn("owner@devnex.app", "devnex123"); b.verifyMfa("123456")
    b.platformSetStatus(agency.companyId, "suspended")
    b.signIn("head@devnex.app", "devnex123")
    check(b.tasks(agency.companyId, null).isEmpty(), "suspended company: no data")
    println("\n$passed demo-backend checks passed")
}
}
