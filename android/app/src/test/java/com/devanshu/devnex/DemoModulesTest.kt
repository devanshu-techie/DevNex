package com.devanshu.devnex

import com.devanshu.devnex.data.demo.DemoRemoteDataSource
import com.devanshu.devnex.domain.model.*
import kotlinx.coroutines.runBlocking
import org.junit.Test

class DemoModulesTest {
    private var passed = 0
    private fun check(ok: Boolean, msg: String) { if (!ok) error("FAILED: $msg"); passed++; println("ok  - $msg") }
    private suspend fun fails(block: suspend () -> Unit): String? = try { block(); null } catch (e: Exception) { e.message ?: "error" }

    @Test fun modulesAndAdministration() = runBlocking {
        val b = DemoRemoteDataSource()
        val news = "c-news"
        // member: publishing, ideas, ops log, DMs, profile
        b.signIn("reporter@devnex.app", "devnex123")
        check(b.slots(news).size == 4, "publishing calendar seeded")
        b.createSlot(news, "Relay reel", "instagram", null, System.currentTimeMillis() + 3_600_000, "caption")
        val mine = b.slots(news).first { it.title == "Relay reel" }
        check(mine.ownerName == "Kabir Shah" && mine.status == "scheduled", "member schedules own slot")
        check(fails { b.publishSlot(mine.id, "not-a-link") } != null, "publishing needs a real link")
        b.publishSlot(mine.id, "https://instagram.com/p/relay")
        check(b.slots(news).first { it.id == mine.id }.status == "published", "member records publication")
        check(fails { b.publishSlot("sl1", "https://x.com/1") } != null, "member can't publish someone else's slot")
        b.createIdea(news, "Drone shots for finals", "", "high", null, null)
        check(fails { b.setIdeaStatus(b.ideas(news).first().id, "approved") } != null, "member can't approve ideas")
        b.addOps(news, "note", "Stadium wifi down, using mobile data")
        check(b.opsLog(news).first().authorName == "Kabir Shah", "ops log entry recorded")
        val dm = b.openDirect(news, "u-neha")
        check(b.openDirect(news, "u-neha") == dm, "DM is reused")
        b.sendMessage("dm-1", news, dm, "Can you cut a 15s clip?")
        check(b.channels(news).any { it.id == dm && it.name == "Neha Iyer" }, "DM shows the other person's name")
        b.updateMyMembership(news, "Senior Reporter", "busy", listOf("athletics", "interviews"))
        check(b.members(news).first { it.userId == "u-kabir" }.availability == "busy", "member updates own availability")
        check(fails { b.auditLog(news) } != null, "member can't read audit log")
        check(fails { b.updateCompanySettings(news, CompanySettings("X", "#000000", "company", 0)) } != null, "member can't change settings")

        // a colleague outside the DM can't read it
        b.signIn("lead@devnex.app", "devnex123")
        check(b.channels(news).none { it.id == dm } && b.messages(dm, null, 50).isEmpty(), "outsider can't see the DM")
        check(fails { b.updateTask("t-4", "", "", Priority.LOW, null) } != null, "empty title rejected")

        // owner: administration
        b.signIn("owner@devnex.app", "devnex123"); b.verifyMfa("123456")
        val idea = b.ideas(news).first { it.title == "Drone shots for finals" }
        b.setIdeaStatus(idea.id, "approved")
        val taskId = b.convertIdea(idea.id)
        check(b.tasks(news, null).first { it.id == taskId }.priority == Priority.HIGH, "idea converts to high-priority task")
        check(b.convertIdea(idea.id) == taskId, "converting twice returns the same task")
        b.createDepartment(news, "Podcast")
        check(b.departments(news).any { it.name == "Podcast" }, "admin creates department")
        check(fails { b.createDepartment(news, "podcast") } != null, "duplicate department rejected")
        val kabir = b.members(news).first { it.userId == "u-kabir" }
        b.setMemberRole(kabir.membershipId, "lead")
        check(b.members(news).first { it.userId == "u-kabir" }.roleKey == "lead", "admin promotes member")
        check(fails { b.setMemberRole(b.members(news).first { it.userId == "u-dev" }.membershipId, "member") } != null, "nobody changes own role")
        b.updateTask(taskId, "Drone shots for the finals", "Book licensed pilot", Priority.CRITICAL, null)
        check(b.tasks(news, null).first { it.id == taskId }.priority == Priority.CRITICAL, "manager edits task")
        b.updateCompanySettings(news, CompanySettings("Velocity Sports", "#0F766E", "company", 90))
        check(b.companySettings(news).brandColor == "#0F766E", "owner updates branding")
        check(fails { b.updateCompanySettings(news, CompanySettings("Velocity", "teal", "company", 90)) } != null, "bad colour rejected")
        check(b.auditLog(news).any { it.action == "member.role" && it.detail.contains("Kabir") }, "role change audited")
        val inv = b.invitations(news).first()
        b.revokeInvitation(inv.id)
        check(b.invitations(news).first { it.id == inv.id }.status == "revoked", "invitation revoked")
        b.signUp("new.reporter@devnex.app", "password123", "New Reporter")
        check(fails { b.acceptInvitation("VELO7X2K9Q") } != null, "revoked invitation can't be used")
        println("\n$passed module checks passed")
    }
}
