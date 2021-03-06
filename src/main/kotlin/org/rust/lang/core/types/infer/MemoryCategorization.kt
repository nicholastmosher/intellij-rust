/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.infer

import org.rust.lang.core.psi.*
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.containerExpr
import org.rust.lang.core.psi.ext.descendantsOfType
import org.rust.lang.core.psi.ext.mutability
import org.rust.lang.core.resolve.ImplLookup
import org.rust.lang.core.types.builtinDeref
import org.rust.lang.core.types.infer.Aliasability.FreelyAliasable
import org.rust.lang.core.types.infer.Aliasability.NonAliasable
import org.rust.lang.core.types.infer.AliasableReason.*
import org.rust.lang.core.types.infer.BorrowKind.ImmutableBorrow
import org.rust.lang.core.types.infer.BorrowKind.MutableBorrow
import org.rust.lang.core.types.infer.Categorization.*
import org.rust.lang.core.types.infer.ImmutabilityBlame.*
import org.rust.lang.core.types.infer.InteriorKind.*
import org.rust.lang.core.types.infer.MutabilityCategory.Declared
import org.rust.lang.core.types.infer.PointerKind.BorrowedPointer
import org.rust.lang.core.types.infer.PointerKind.UnsafePointer
import org.rust.lang.core.types.isDereference
import org.rust.lang.core.types.regions.ReStatic
import org.rust.lang.core.types.regions.Region
import org.rust.lang.core.types.ty.*
import org.rust.stdext.nextOrNull

/** [Categorization] is a subset of the full expression forms */
sealed class Categorization {
    /** Temporary value */
    data class Rvalue(val region: Region) : Categorization()

    /** Static value */
    object StaticItem : Categorization()

    /** Local variable */
    data class Local(val element: RsElement) : Categorization()

    /** Dereference of a pointer */
    data class Deref(val cmt: Cmt, val pointerKind: PointerKind) : Categorization()

    /** Something reachable from the base without a pointer dereference (e.g. field) */
    data class Interior(val cmt: Cmt, val interiorKind: InteriorKind) : Categorization()

    /** Selects a particular enum variant (if enum has more than one variant */
    data class Downcast(val cmt: Cmt, val element: RsElement) : Categorization()
}

sealed class BorrowKind {
    object ImmutableBorrow : BorrowKind()
    object MutableBorrow : BorrowKind()

    companion object {
        fun from(mutability: Mutability): BorrowKind =
            when (mutability) {
                Mutability.IMMUTABLE -> ImmutableBorrow
                Mutability.MUTABLE -> MutableBorrow
            }

        fun isCompatible(firstKind: BorrowKind, secondKind: BorrowKind): Boolean =
            firstKind == ImmutableBorrow && secondKind == ImmutableBorrow
    }
}

sealed class PointerKind {
    data class BorrowedPointer(val borrowKind: BorrowKind, val region: Region) : PointerKind()
    data class UnsafePointer(val mutability: Mutability) : PointerKind()
}

/** "interior" means "something reachable from the base without a pointer dereference" */
sealed class InteriorKind {
    /** e.g. `s.field` */
    class InteriorField(val fieldName: String?) : InteriorKind()

    /** e.g. `arr[0]` */
    object InteriorIndex : InteriorKind()

    /** e.g. `fn foo([_, a, _, _]: [A; 4]) { ... }` */
    object InteriorPattern : InteriorKind()
}

/** Reason why something is immutable */
sealed class ImmutabilityBlame {
    /** Immutable as immutable variable */
    class LocalDeref(val element: RsElement) : ImmutabilityBlame()

    /** Immutable as dereference of immutable variable */
    object AdtFieldDeref : ImmutabilityBlame()

    /** Immutable as interior of immutable */
    class ImmutableLocal(val element: RsElement) : ImmutabilityBlame()
}

/**
 * Borrow checker have to never permit &mut-borrows of aliasable data.
 * "Rules" of aliasability:
 * - Local variables are never aliasable as they are accessible only within the stack frame;
 * - Owned content is aliasable if it is found in an aliasable location;
 * - `&T` is aliasable, and hence can only be borrowed immutably;
 * - `&mut T` is aliasable if `T` is aliasable
 */
sealed class Aliasability {
    class FreelyAliasable(val reason: AliasableReason) : Aliasability()
    object NonAliasable : Aliasability()
}

enum class AliasableReason {
    Borrowed,
    Static,
    StaticMut
}

/** Mutability of the expression address */
enum class MutabilityCategory {
    /** Any immutable */
    Immutable,
    /** Directly declared as mutable */
    Declared,
    /** Inherited from the fact that owner is mutable */
    Inherited;

    companion object {
        fun from(mutability: Mutability): MutabilityCategory =
            when (mutability) {
                Mutability.IMMUTABLE -> Immutable
                Mutability.MUTABLE -> Declared
            }

        fun from(borrowKind: BorrowKind): MutabilityCategory =
            when (borrowKind) {
                is ImmutableBorrow -> Immutable
                is MutableBorrow -> Declared
            }

        fun from(pointerKind: PointerKind): MutabilityCategory =
            when (pointerKind) {
                is BorrowedPointer -> from(pointerKind.borrowKind)
                is UnsafePointer -> from(pointerKind.mutability)
            }
    }

    fun inherit(): MutabilityCategory =
        when (this) {
            Immutable -> Immutable
            Declared, Inherited -> Inherited
        }

    val isMutable: Boolean get() = this != Immutable
}

/**
 * [Cmt]: Category, MutabilityCategory, and Type
 *
 * Imagine a routine Address(Expr) that evaluates an expression and returns an
 * address where the result is to be found.  If Expr is a place, then this
 * is the address of the place.  If Expr is an rvalue, this is the address of
 * some temporary spot in memory where the result is stored.
 *
 * [element]: Expr
 * [category]: kind of Expr
 * [mutabilityCategory]: mutability of Address(Expr)
 * [ty]: the type of data found at Address(Expr)
 */
class Cmt(
    val element: RsElement,
    val category: Categorization? = null,
    val mutabilityCategory: MutabilityCategory = MutabilityCategory.from(Mutability.DEFAULT_MUTABILITY),
    val ty: Ty
) {
    val immutabilityBlame: ImmutabilityBlame?
        get() = when (category) {
            is Deref -> {
                // try to figure out where the immutable reference came from
                val pointerKind = category.pointerKind
                val baseCmt = category.cmt
                if (pointerKind is BorrowedPointer && pointerKind.borrowKind is ImmutableBorrow) {
                    when (baseCmt.category) {
                        is Local -> LocalDeref(baseCmt.category.element)
                        is Interior -> AdtFieldDeref
                        else -> null
                    }
                } else if (pointerKind is UnsafePointer) {
                    null
                } else {
                    baseCmt.immutabilityBlame
                }
            }
            is Local -> ImmutableLocal(category.element)
            is Interior -> category.cmt.immutabilityBlame
            is Downcast -> category.cmt.immutabilityBlame
            else -> null
        }

    val isMutable: Boolean get() = mutabilityCategory.isMutable

    val aliasability: Aliasability
        get() = when {
            category is Deref && category.pointerKind is BorrowedPointer ->
                when (category.pointerKind.borrowKind) {
                    is MutableBorrow -> category.cmt.aliasability
                    is ImmutableBorrow -> FreelyAliasable(Borrowed)
                }
            category is StaticItem -> FreelyAliasable(if (isMutable) StaticMut else Static)
            category is Interior -> category.cmt.aliasability
            category is Downcast -> category.cmt.aliasability
            else -> NonAliasable
        }
}

class MemoryCategorizationContext(val lookup: ImplLookup, val inference: RsInferenceData) {
    fun processExpr(expr: RsExpr): Cmt {
        val adjustments = inference.getExprAdjustments(expr)
        return processExprAdjustedWith(expr, adjustments.asReversed().iterator())
    }

    private fun processExprAdjustedWith(expr: RsExpr, adjustments: Iterator<Adjustment>): Cmt =
        when (adjustments.nextOrNull()) {
            is Adjustment.Deref -> {
                // TODO: overloaded deref
                processDeref(expr, processExprAdjustedWith(expr, adjustments))
            }
            else -> processExprUnadjusted(expr)
        }

    private fun processExprUnadjusted(expr: RsExpr): Cmt =
        when (expr) {
            is RsUnaryExpr -> processUnaryExpr(expr)
            is RsDotExpr -> processDotExpr(expr)
            is RsIndexExpr -> processIndexExpr(expr)
            is RsPathExpr -> processPathExpr(expr)
            is RsParenExpr -> processParenExpr(expr)
            else -> processRvalue(expr)
        }

    private fun processUnaryExpr(unaryExpr: RsUnaryExpr): Cmt {
        if (!unaryExpr.isDereference) return processRvalue(unaryExpr)
        val base = unaryExpr.expr ?: return Cmt(unaryExpr, ty = inference.getExprType(unaryExpr))
        val baseCmt = processExpr(base)
        return processDeref(unaryExpr, baseCmt)
    }

    private fun processDotExpr(dotExpr: RsDotExpr): Cmt {
        if (dotExpr.methodCall != null) return processRvalue(dotExpr)
        val type = inference.getExprType(dotExpr)
        val base = dotExpr.expr
        val baseCmt = processExpr(base)
        val fieldName = dotExpr.fieldLookup?.identifier?.text ?: dotExpr.fieldLookup?.integerLiteral?.text
        return cmtOfField(dotExpr, baseCmt, fieldName, type)
    }

    private fun processIndexExpr(indexExpr: RsIndexExpr): Cmt {
        val type = inference.getExprType(indexExpr)
        val base = indexExpr.containerExpr ?: return Cmt(indexExpr, ty = type)
        val baseCmt = processExpr(base)
        return Cmt(indexExpr, Interior(baseCmt, InteriorIndex), baseCmt.mutabilityCategory.inherit(), type)
    }

    private fun processPathExpr(pathExpr: RsPathExpr): Cmt {
        val type = inference.getExprType(pathExpr)
        // TODO: infcx.getResolvedPaths
        val declaration = inference.getResolvedPaths(pathExpr).singleOrNull() ?: return Cmt(pathExpr, ty = type)
        return when (declaration) {
            is RsConstant -> {
                if (declaration.static != null) {
                    Cmt(pathExpr, StaticItem, MutabilityCategory.from(declaration.mutability), type)
                } else {
                    processRvalue(pathExpr)
                }
            }

            is RsEnumVariant, is RsStructItem, is RsFunction -> processRvalue(pathExpr)

            is RsPatBinding -> Cmt(pathExpr, Local(declaration), MutabilityCategory.from(declaration.mutability), type)

            is RsSelfParameter -> Cmt(pathExpr, Local(declaration), MutabilityCategory.from(declaration.mutability), type)

            else -> Cmt(pathExpr, ty = type)
        }
    }

    private fun processParenExpr(parenExpr: RsParenExpr): Cmt =
        processExpr(parenExpr.expr)

    private fun processDeref(expr: RsExpr, baseCmt: Cmt): Cmt {
        val baseType = baseCmt.ty
        val (derefType, derefMut) = baseType.builtinDeref() ?: Pair(TyUnknown, Mutability.DEFAULT_MUTABILITY)

        val pointerKind = when (baseType) {
            is TyReference -> BorrowedPointer(BorrowKind.from(baseType.mutability), baseType.region)
            is TyPointer -> UnsafePointer(baseType.mutability)
            else -> UnsafePointer(derefMut)
        }

        return Cmt(expr, Deref(baseCmt, pointerKind), MutabilityCategory.from(pointerKind), derefType)
    }

    // `rvalue_promotable_map` is needed to distinguish rvalues with static region and rvalue with temporary region,
    // so now all rvalues have static region
    fun processRvalue(expr: RsExpr, ty: Ty = inference.getExprType(expr)): Cmt =
        Cmt(expr, Rvalue(ReStatic), Declared, ty)

    fun processRvalue(element: RsElement, tempScope: Region, ty: Ty): Cmt =
        Cmt(element, Rvalue(tempScope), Declared, ty)

    fun walkPat(cmt: Cmt, pat: RsPat, callback: (Cmt, RsPat) -> Unit) {
        fun processTuplePats(pats: List<RsPat>) {
            for ((index, subPat) in pats.withIndex()) {
                val subBinding = subPat.descendantsOfType<RsPatBinding>().firstOrNull() ?: continue
                val subType = inference.getBindingType(subBinding)
                val interior = InteriorField(index.toString())
                val subCmt = Cmt(pat, Interior(cmt, interior), cmt.mutabilityCategory.inherit(), subType)
                walkPat(subCmt, subPat, callback)
            }
        }

        callback(cmt, pat)

        when (pat) {
            is RsPatIdent -> {
                if (pat.patBinding.reference.resolve() !is RsEnumVariant) {
                    pat.pat?.let { walkPat(cmt, it, callback) }
                }
            }

            is RsPatTupleStruct -> processTuplePats(pat.patList)

            is RsPatTup -> processTuplePats(pat.patList)

            is RsPatStruct -> {
                for (patField in pat.patFieldList) {
                    val binding = patField.patBinding ?: continue
                    val fieldType = inference.getBindingType(binding)
                    val fieldName = patField.identifier?.text ?: continue
                    val fieldPat = patField.pat ?: continue
                    val fieldCmt = cmtOfField(pat, cmt, fieldName, fieldType)
                    walkPat(fieldCmt, fieldPat, callback)
                }
            }

            is RsPatSlice -> {
                val elementCmt = cmtOfSliceElement(pat, cmt)
                pat.patList.forEach { walkPat(elementCmt, it, callback) }
            }
        }
    }

    private fun cmtOfField(element: RsElement, baseCmt: Cmt, fieldName: String?, fieldType: Ty): Cmt =
        Cmt(
            element,
            Interior(baseCmt, InteriorField(fieldName)),
            baseCmt.mutabilityCategory.inherit(),
            fieldType
        )

    private fun cmtOfSliceElement(element: RsElement, baseCmt: Cmt): Cmt =
        Cmt(
            element,
            Interior(baseCmt, InteriorPattern),
            baseCmt.mutabilityCategory.inherit(),
            baseCmt.ty
        )

    fun isTypeMovesByDefault(ty: Ty): Boolean =
        when (ty) {
            is TyUnknown, is TyPrimitive, is TyTuple, is TyReference, is TyPointer, is TyFunction -> false
            else -> lookup.isCopy(ty).not()
        }
}
