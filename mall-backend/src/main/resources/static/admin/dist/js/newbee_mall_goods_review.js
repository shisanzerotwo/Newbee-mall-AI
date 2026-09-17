$(function () {
    $("#jqGrid").jqGrid({
        url: '/admin/goodsReviews/list',
        datatype: "json",
        postData: {
            goodsId: $("#filterGoodsId").val(),
            userId: $("#filterUserId").val()
        },
        colModel: [
            {label: 'id', name: 'reviewId', index: 'reviewId', width: 50, key: true, hidden: true},
            {label: '商品ID', name: 'goodsId', index: 'goodsId', width: 80},
            {label: '用户', name: 'nickName', index: 'nickName', width: 120},
            {label: '用户ID', name: 'userId', index: 'userId', width: 80},
            {label: '评分', name: 'reviewScore', index: 'reviewScore', width: 80, formatter: scoreFormatter},
            {label: '评论内容', name: 'reviewContent', index: 'reviewContent', width: 400},
            {label: '评论时间', name: 'createTime', index: 'createTime', width: 150}
        ],
        height: 560,
        rowNum: 10,
        rowList: [10, 20, 50],
        styleUI: 'Bootstrap',
        loadtext: '信息读取中...',
        rownumbers: false,
        rownumWidth: 20,
        autowidth: true,
        multiselect: true,
        pager: "#jqGridPager",
        jsonReader: {
            root: "data.list",
            page: "data.currPage",
            total: "data.totalPage",
            records: "data.totalCount"
        },
        prmNames: {
            page: "page",
            rows: "limit",
            order: "order",
        },
        gridComplete: function () {
            //隐藏grid底部滚动条
            $("#jqGrid").closest(".ui-jqgrid-bdiv").css({"overflow-x": "hidden"});
        }
    });

    $(window).resize(function () {
        $("#jqGrid").setGridWidth($(".card-body").width());
    });

    function scoreFormatter(cellvalue) {
        if (cellvalue == null) {
            return "";
        }
        var star = "";
        for (var i = 0; i < cellvalue; i++) {
            star += "★";
        }
        return "<span style='color:#f05b72;'>" + star + "</span>";
    }
});

/**
 * 按筛选条件查询
 */
function searchFilter() {
    $("#jqGrid").jqGrid('setGridParam', {
        postData: {
            goodsId: $("#filterGoodsId").val(),
            userId: $("#filterUserId").val()
        },
        page: 1
    }).trigger("reloadGrid");
}

/**
 * 重置筛选条件
 */
function resetFilter() {
    $("#filterGoodsId").val("");
    $("#filterUserId").val("");
    $("#jqGrid").jqGrid('setGridParam', {
        postData: {
            goodsId: "",
            userId: ""
        },
        page: 1
    }).trigger("reloadGrid");
}

/**
 * 批量删除评论
 */
function deleteGoodsReview() {
    var ids = getSelectedRows();
    if (ids == null) {
        return;
    }
    Swal.fire({
        title: "确认弹框",
        text: "确认要删除选中的评论吗?",
        icon: "warning", iconColor: "#dea32c",
        showCancelButton: true,
        confirmButtonText: '确认',
        cancelButtonText: '取消'
    }).then((flag) => {
        if (flag.value) {
            $.ajax({
                type: "POST",
                url: "/admin/goodsReviews/delete",
                contentType: "application/json",
                data: JSON.stringify(ids),
                success: function (r) {
                    if (r.resultCode == 200) {
                        Swal.fire({
                            text: "操作成功",
                            icon: "success", iconColor: "#1d953f",
                        });
                        $("#jqGrid").trigger("reloadGrid");
                    } else {
                        Swal.fire({
                            text: r.message,
                            icon: "error", iconColor: "#f05b72",
                        });
                    }
                }
            });
        }
    });
}
